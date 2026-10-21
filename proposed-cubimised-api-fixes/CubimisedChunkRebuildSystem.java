package dev.cubimised.api.client.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.chunk.ChunkRendererRegion;
import net.minecraft.client.render.chunk.ChunkRendererRegionBuilder;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Camera-prioritized dirty chunk-section rebuild queue. */
public final class CubimisedChunkRebuildSystem {
    private static final int CHUNK_MARKER = Integer.MIN_VALUE;

    private final PriorityQueue<Work> queue = new PriorityQueue<>(
            (a, b) -> Double.compare(a.distanceSq, b.distanceSq));
    private final Set<Long> queuedChunks = new HashSet<>();
    private final Set<SectionKey> queuedSections = new HashSet<>();
    private final Map<Long, Integer> remainingSections = new HashMap<>();
    private final Map<Long, Boolean> chunkBuildSucceeded = new HashMap<>();
    private final ChunkRendererRegionBuilder regions = new ChunkRendererRegionBuilder();
    private ClientWorld queuedWorld;

    public void beginFrame() {}

    public void enqueue(ChunkPos pos, double distanceSq) {
        if (pos != null && queuedChunks.add(pos.toLong())) {
            queue.offer(new Work(pos, CHUNK_MARKER, distanceSq));
        }
    }

    public void enqueueAround(Camera camera, int radius) {
        if (camera == null) return;
        int cx = ((int) Math.floor(camera.getPos().x)) >> 4;
        int cz = ((int) Math.floor(camera.getPos().z)) >> 4;
        int r = Math.max(1, radius);
        for (int z = cz - r; z <= cz + r; z++) {
            for (int x = cx - r; x <= cx + r; x++) {
                ChunkPos p = new ChunkPos(x, z);
                enqueue(p, CubimisedVisibility.distanceSq(camera, p));
            }
        }
    }

    public int process(MinecraftClient client, CubimisedTerrainRenderer terrain, int budget) {
        if (client == null || client.world == null) {
            resetQueue();
            queuedWorld = null;
            return 0;
        }

        ClientWorld world = client.world;
        if (queuedWorld != world) {
            resetQueue();
            queuedWorld = world;
        }

        if (terrain == null || budget <= 0) return 0;

        int processed = 0;
        while (processed < budget && !queue.isEmpty()) {
            Work work = queue.poll();
            long chunkKey = work.pos.toLong();

            if (work.sectionY == CHUNK_MARKER) {
                terrain.markChunkDirty(work.pos);
                expandChunkWork(world, work, chunkKey);
                continue;
            }

            SectionKey key = new SectionKey(chunkKey, work.sectionY);
            queuedSections.remove(key);

            int worldMinY = world.getBottomY();
            int worldMaxY = world.getTopY() - 1;
            int sectionMin = Math.max(worldMinY, work.sectionY * 16);
            int sectionMax = Math.min(worldMaxY, work.sectionY * 16 + 15);

            if (sectionMin <= sectionMax) {
                BlockPos origin = new BlockPos(work.pos.getStartX(), sectionMin, work.pos.getStartZ());
                BlockPos end = new BlockPos(work.pos.getEndX(), sectionMax, work.pos.getEndZ());
                ChunkRendererRegion region = regions.build(world, origin, end, 1);
                if (region != null) {
                    terrain.replaceSection(work.pos, work.sectionY,
                            CubimisedChunkMesher.mesh(region, origin, sectionMin, sectionMax));
                } else {
                    chunkBuildSucceeded.put(chunkKey, false);
                }
            } else {
                chunkBuildSucceeded.put(chunkKey, false);
            }

            processed++;
            int remaining = remainingSections.getOrDefault(chunkKey, 1) - 1;
            if (remaining <= 0) {
                remainingSections.remove(chunkKey);
                queuedChunks.remove(chunkKey);
                boolean success = chunkBuildSucceeded.getOrDefault(chunkKey, false);
                chunkBuildSucceeded.remove(chunkKey);
                if (success) terrain.markChunkCovered(work.pos);
            } else {
                remainingSections.put(chunkKey, remaining);
            }
        }
        return processed;
    }

    private void expandChunkWork(ClientWorld world, Work work, long chunkKey) {
        int minY = world.getBottomY();
        int maxY = world.getTopY() - 1;
        if (minY > maxY) {
            queuedChunks.remove(chunkKey);
            return;
        }

        int minSection = Math.floorDiv(minY, 16);
        int maxSection = Math.floorDiv(maxY, 16);
        int count = maxSection - minSection + 1;
        remainingSections.put(chunkKey, count);
        chunkBuildSucceeded.put(chunkKey, true);

        for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
            SectionKey key = new SectionKey(chunkKey, sectionY);
            if (queuedSections.add(key)) {
                queue.offer(new Work(work.pos, sectionY, work.distanceSq));
            }
        }

        if (queuedSections.stream().noneMatch(key -> key.chunkKey == chunkKey)) {
            remainingSections.remove(chunkKey);
            chunkBuildSucceeded.remove(chunkKey);
            queuedChunks.remove(chunkKey);
        } else {
            int actualCount = 0;
            for (SectionKey key : queuedSections) {
                if (key.chunkKey == chunkKey) actualCount++;
            }
            remainingSections.put(chunkKey, actualCount);
        }
    }

    public void markDirty(ChunkPos pos) { enqueue(pos, 0.0); }
    public int queuedCount() { return queue.size(); }

    private void resetQueue() {
        queue.clear();
        queuedChunks.clear();
        queuedSections.clear();
        remainingSections.clear();
        chunkBuildSucceeded.clear();
    }

    private record Work(ChunkPos pos, int sectionY, double distanceSq) {}
    private record SectionKey(long chunkKey, int sectionY) {}
}
