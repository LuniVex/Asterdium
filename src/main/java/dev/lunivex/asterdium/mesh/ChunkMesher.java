package dev.lunivex.asterdium.mesh;

import java.util.Arrays;

/**
 * Simple, original six-face voxel mesher. This is a correctness baseline, not
 * a claim of performance superiority: no greedy meshing, material IDs, light
 * interpolation, fluids, transparency or texture atlas support yet.
 */
public final class ChunkMesher {
    public static final int SECTION_SIZE = 16;

    private ChunkMesher() { }

    public static ChunkMesh build(VoxelView view, int originX, int originY, int originZ) {
        if (view == null) throw new IllegalArgumentException("view cannot be null");
        Builder output = new Builder();

        for (int z = 0; z < SECTION_SIZE; z++) {
            for (int y = 0; y < SECTION_SIZE; y++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int wx = originX + x;
                    int wy = originY + y;
                    int wz = originZ + z;
                    if (!view.isOpaque(wx, wy, wz)) continue;

                    for (Face face : Face.values()) {
                        if (!view.isOpaque(wx + face.nx, wy + face.ny, wz + face.nz)) {
                            output.addFace(x, y, z, face);
                        }
                    }
                }
            }
        }
        return output.finish();
    }

    // Corner winding is counter-clockwise when viewed from outside the solid.
    private enum Face {
        POS_X(1, 0, 0, new int[][] {{1,0,0},{1,1,0},{1,1,1},{1,0,1}}),
        NEG_X(-1, 0, 0, new int[][] {{0,0,1},{0,1,1},{0,1,0},{0,0,0}}),
        POS_Y(0, 1, 0, new int[][] {{0,1,1},{1,1,1},{1,1,0},{0,1,0}}),
        NEG_Y(0, -1, 0, new int[][] {{0,0,0},{1,0,0},{1,0,1},{0,0,1}}),
        POS_Z(0, 0, 1, new int[][] {{0,0,1},{1,0,1},{1,1,1},{0,1,1}}),
        NEG_Z(0, 0, -1, new int[][] {{0,1,0},{1,1,0},{1,0,0},{0,0,0}});

        final int nx, ny, nz;
        final int[][] corners;

        Face(int nx, int ny, int nz, int[][] corners) {
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.corners = corners;
        }
    }

    private static final class Builder {
        private static final int[][] UV = {{0,0},{1,0},{1,1},{0,1}};
        private float[] vertices = new float[128];
        private int[] indices = new int[96];
        private int vertexLength;
        private int indexLength;

        void addFace(int x, int y, int z, Face face) {
            int firstVertex = vertexLength / ChunkMesh.FLOATS_PER_VERTEX;
            ensureVertexSpace(4 * ChunkMesh.FLOATS_PER_VERTEX);
            ensureIndexSpace(6);

            for (int i = 0; i < 4; i++) {
                int[] corner = face.corners[i];
                vertices[vertexLength++] = x + corner[0];
                vertices[vertexLength++] = y + corner[1];
                vertices[vertexLength++] = z + corner[2];
                vertices[vertexLength++] = UV[i][0];
                vertices[vertexLength++] = UV[i][1];
                vertices[vertexLength++] = face.nx;
                vertices[vertexLength++] = face.ny;
                vertices[vertexLength++] = face.nz;
            }

            indices[indexLength++] = firstVertex;
            indices[indexLength++] = firstVertex + 1;
            indices[indexLength++] = firstVertex + 2;
            indices[indexLength++] = firstVertex;
            indices[indexLength++] = firstVertex + 2;
            indices[indexLength++] = firstVertex + 3;
        }

        private void ensureVertexSpace(int count) {
            if (vertices.length < vertexLength + count) {
                vertices = Arrays.copyOf(vertices, Math.max(vertices.length * 2, vertexLength + count));
            }
        }

        private void ensureIndexSpace(int count) {
            if (indices.length < indexLength + count) {
                indices = Arrays.copyOf(indices, Math.max(indices.length * 2, indexLength + count));
            }
        }

        ChunkMesh finish() {
            return new ChunkMesh(Arrays.copyOf(vertices, vertexLength), Arrays.copyOf(indices, indexLength));
        }
    }
}
