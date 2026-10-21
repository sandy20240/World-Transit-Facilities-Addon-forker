package dev.cubimised.api.client.render;

import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.gl.ShaderProgram;
import org.joml.Matrix4f;

/** Reusable render-thread GPU vertex buffer. */
public final class CubimisedGpuBuffer implements AutoCloseable {
    private final VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
    private boolean uploaded;
    private long uploadedBytes;

    public void upload(CubimisedChunkMesh mesh) {
        uploaded = false;
        uploadedBytes = 0L;
        if (mesh == null || mesh.isEmpty()) return;

        buffer.bind();
        try {
            buffer.upload(mesh.builtBuffer());
            uploaded = true;
            uploadedBytes = mesh.vertexBytes();
        } finally {
            VertexBuffer.unbind();
        }
    }

    public void draw() {
        if (!uploaded) return;
        buffer.bind();
        try {
            buffer.draw();
        } finally {
            VertexBuffer.unbind();
        }
    }

    public void draw(Matrix4f view, Matrix4f projection, ShaderProgram shader) {
        if (!uploaded || shader == null) return;
        buffer.bind();
        try {
            buffer.draw(view, projection, shader);
        } finally {
            VertexBuffer.unbind();
        }
    }

    public boolean isUploaded() { return uploaded; }
    public long uploadedBytes() { return uploadedBytes; }

    @Override public void close() {
        buffer.close();
        uploaded = false;
        uploadedBytes = 0L;
    }
}
