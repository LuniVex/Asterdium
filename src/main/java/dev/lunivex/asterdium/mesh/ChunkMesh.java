package dev.lunivex.asterdium.mesh;

import java.util.Arrays;

/**
 * CPU-side indexed geometry. Each vertex has eight floats:
 * position.xyz, uv.xy, normal.xyz. Geometry is local to the section origin.
 */
public final class ChunkMesh {
    public static final int FLOATS_PER_VERTEX = 8;

    private final float[] vertices;
    private final int[] indices;

    ChunkMesh(float[] vertices, int[] indices) {
        if (vertices.length % FLOATS_PER_VERTEX != 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("Invalid indexed triangle mesh layout");
        }
        this.vertices = vertices;
        this.indices = indices;
    }

    public int vertexCount() { return vertices.length / FLOATS_PER_VERTEX; }
    public int triangleCount() { return indices.length / 3; }
    public int indexCount() { return indices.length; }
    public int quadCount() { return indices.length / 6; }

    public float[] vertices() { return Arrays.copyOf(vertices, vertices.length); }
    public int[] indices() { return Arrays.copyOf(indices, indices.length); }
}
