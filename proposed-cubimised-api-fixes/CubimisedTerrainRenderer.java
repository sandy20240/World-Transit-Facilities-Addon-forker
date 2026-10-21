package dev.cubimised.api.client.render;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.util.math.ChunkPos;
import org.joml.Matrix4f;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Cubimised terrain GPU backend.
 *
 * Meshes are stored per chunk (legacy entry point) or per chunk section.
 * CPU buffers are released after upload; GPU buffers are reused through a pool.
 */
public final class CubimisedTerrainRenderer implements AutoCloseable {
    private final Map<Long, ChunkGpuData> chunks = new HashMap<>();
    private final Map<SectionKey, ChunkGpuData> sections = new HashMap<>();
    private final ArrayDeque<CubimisedGpuBuffer> pool = new ArrayDeque<>();
    private long uploadedBytes;
    private long drawCalls;
    private long visibleChunks;
    private long culledChunks;
    private final Set<Long> coveredChunks = new HashSet<>();

    public void replaceChunk(ChunkPos pos, CubimisedChunkRenderData data) {
        if (pos == null || data == null) return;
        ChunkGpuData old = chunks.remove(pos.toLong());
        releaseActiveData(old);

        ChunkGpuData gpu = uploadMeshes(data);
        chunks.put(pos.toLong(), gpu);
        coveredChunks.add(pos.toLong());
    }

    /** Legacy layer upload entry point. */
    public void upload(RenderLayer layer, net.minecraft.client.render.BufferBuilder.BuiltBuffer mesh) {
        if (layer == null || mesh == null || mesh.isEmpty()) return;
        CubimisedGpuBuffer buffer = pool.pollFirst();
        if (buffer == null) buffer = new CubimisedGpuBuffer();
        try {
            buffer.upload(new CubimisedChunkMesh(mesh));
        } finally {
            pool.offerLast(buffer);
        }
    }

    /** Draws Cubimised buffers while the vanilla RenderLayer state/shader is active. */
    public boolean renderLayer(RenderLayer layer, double cameraX, double cameraY, double cameraZ) {
        if (layer == null || (chunks.isEmpty() && sections.isEmpty())) return false;
        ArrayList<ChunkGpuData> visible = new ArrayList<>();
        double maxDistance = 512.0;
        double maxSq = maxDistance * maxDistance;

        collectVisible(chunks.values(), layer, cameraX, cameraY, cameraZ, maxSq, visible);
        collectVisible(sections.values(), layer, cameraX, cameraY, cameraZ, maxSq, visible);
        if (visible.isEmpty()) return false;

        if (layer == RenderLayer.getTranslucent() || layer == RenderLayer.getTranslucentMovingBlock()) {
            visible.sort(Comparator.comparingDouble(
                    chunk -> -distanceSq(chunk.origin, cameraX, cameraY, cameraZ)));
        }

        layer.startDrawing();
        try {
            for (ChunkGpuData chunk : visible) {
                CubimisedGpuBuffer buffer = chunk.buffers.get(layer);
                if (buffer != null && buffer.isUploaded()) {
                    buffer.draw();
                    drawCalls++;
                }
            }
        } finally {
            layer.endDrawing();
        }
        visibleChunks = visible.size();
        return true;
    }

    private static void collectVisible(
            Iterable<ChunkGpuData> source,
            RenderLayer layer,
            double cameraX,
            double cameraY,
            double cameraZ,
            double maxSq,
            ArrayList<ChunkGpuData> output
    ) {
        for (ChunkGpuData chunk : source) {
            CubimisedGpuBuffer buffer = chunk.buffers.get(layer);
            if (buffer == null || !buffer.isUploaded()) continue;
            double dx = chunk.origin.getX() + 8.0 - cameraX;
            double dy = chunk.origin.getY() + 8.0 - cameraY;
            double dz = chunk.origin.getZ() + 8.0 - cameraZ;
            if (dx * dx + dy * dy + dz * dz <= maxSq) output.add(chunk);
        }
    }

    private static double distanceSq(net.minecraft.util.math.BlockPos pos, double x, double y, double z) {
        double dx = pos.getX() + 8.0 - x;
        double dy = pos.getY() + 8.0 - y;
        double dz = pos.getZ() + 8.0 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public boolean hasCoverage(Camera camera, int radius) {
        if (camera == null) return false;
        int cx = ((int) Math.floor(camera.getPos().x)) >> 4;
        int cz = ((int) Math.floor(camera.getPos().z)) >> 4;
        int r = Math.max(1, radius);
        for (int z = cz - r; z <= cz + r; z++) {
            for (int x = cx - r; x <= cx + r; x++) {
                if (!coveredChunks.contains(new ChunkPos(x, z).toLong())) return false;
            }
        }
        return true;
    }

    public void submit(RenderLayer layer, Matrix4f view, Matrix4f projection, ShaderProgram shader) {
        if (layer == null) return;
        layer.startDrawing();
        try {
            submitData(chunks.values(), layer, view, projection, shader);
            submitData(sections.values(), layer, view, projection, shader);
            visibleChunks = chunks.size() + sections.size();
        } finally {
            layer.endDrawing();
        }
    }

    private void submitData(
            Iterable<ChunkGpuData> source,
            RenderLayer layer,
            Matrix4f view,
            Matrix4f projection,
            ShaderProgram shader
    ) {
        for (ChunkGpuData chunk : source) {
            CubimisedGpuBuffer buffer = chunk.buffers.get(layer);
            if (buffer == null || !buffer.isUploaded()) continue;
            buffer.draw(view, projection, shader);
            drawCalls++;
        }
    }

    /** Replaces a single 16x16x16 section without touching neighboring sections. */
    public void replaceSection(ChunkPos pos, int sectionY, CubimisedChunkRenderData data) {
        if (pos == null || data == null) return;
        SectionKey key = new SectionKey(pos.toLong(), sectionY);
        ChunkGpuData old = sections.remove(key);
        releaseActiveData(old);

        ChunkGpuData gpu = uploadMeshes(data);
        sections.put(key, gpu);
    }

    public void markChunkDirty(ChunkPos pos) {
        if (pos != null) coveredChunks.remove(pos.toLong());
    }

    public void markChunkCovered(ChunkPos pos) {
        if (pos != null) coveredChunks.add(pos.toLong());
    }

    private ChunkGpuData uploadMeshes(CubimisedChunkRenderData data) {
        ChunkGpuData gpu = new ChunkGpuData(data.origin());
        try {
            for (Map.Entry<RenderLayer, net.minecraft.client.render.BufferBuilder.BuiltBuffer> entry
                    : data.meshes().entrySet()) {
                CubimisedGpuBuffer buffer = pool.pollFirst();
                if (buffer == null) buffer = new CubimisedGpuBuffer();
                buffer.upload(new CubimisedChunkMesh(entry.getValue()));
                if (buffer.isUploaded()) {
                    gpu.buffers.put(entry.getKey(), buffer);
                    uploadedBytes += buffer.uploadedBytes();
                } else {
                    pool.offerFirst(buffer);
                }
            }
            return gpu;
        } finally {
            data.release();
        }
    }

    private void releaseActiveData(ChunkGpuData data) {
        if (data == null) return;
        for (CubimisedGpuBuffer buffer : data.buffers.values()) {
            uploadedBytes = Math.max(0L, uploadedBytes - buffer.uploadedBytes());
            if (buffer.isUploaded()) pool.offerLast(buffer);
            else buffer.close();
        }
        data.buffers.clear();
    }

    public void clearChunk(ChunkPos pos) {
        if (pos == null) return;
        releaseActiveData(chunks.remove(pos.toLong()));

        sections.entrySet().removeIf(entry -> {
            if (entry.getKey().chunkKey() != pos.toLong()) return false;
            releaseActiveData(entry.getValue());
            return true;
        });
        coveredChunks.remove(pos.toLong());
    }

    public int chunkCount() { return chunks.size() + sections.size(); }
    public long uploadedBytes() { return uploadedBytes; }
    public long drawCalls() { return drawCalls; }
    public long visibleChunks() { return visibleChunks; }
    public long culledChunks() { return culledChunks; }

    @Override public void close() {
        for (ChunkGpuData data : chunks.values()) releaseActiveData(data);
        for (ChunkGpuData data : sections.values()) releaseActiveData(data);
        chunks.clear();
        sections.clear();
        coveredChunks.clear();
        uploadedBytes = 0L;
        while (!pool.isEmpty()) pool.poll().close();
    }

    private record SectionKey(long chunkKey, int sectionY) {}

    private static final class ChunkGpuData {
        final net.minecraft.util.math.BlockPos origin;
        final Map<RenderLayer, CubimisedGpuBuffer> buffers = new IdentityHashMap<>();

        ChunkGpuData(net.minecraft.util.math.BlockPos origin) {
            this.origin = origin.toImmutable();
        }
    }
}
