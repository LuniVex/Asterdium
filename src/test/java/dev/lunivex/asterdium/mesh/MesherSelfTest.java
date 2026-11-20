package dev.lunivex.asterdium.mesh;

import java.util.Arrays;

/** Run without Gradle or Minecraft: java dev.lunivex.asterdium.mesh.MesherSelfTest */
public final class MesherSelfTest {
    private MesherSelfTest() { }

    public static void main(String[] args) {
        assertQuads("empty section", (x,y,z) -> false, 0);
        assertQuads("one block", (x,y,z) -> x == 0 && y == 0 && z == 0, 6);
        assertQuads("two touching blocks", (x,y,z) -> y == 0 && z == 0 && (x == 0 || x == 1), 10);
        assertQuads("completely filled section", (x,y,z) ->
                x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16, 1536);
        assertQuads("neighboring section hides one edge", (x,y,z) ->
                (x == 0 || x == -1) && y == 0 && z == 0, 5);
        assertQuads("non-zero origin", (x,y,z) -> x == 32 && y == 64 && z == -16, 6,
                32, 64, -16);

        ChunkMesh cube = ChunkMesher.build((x,y,z) -> x == 0 && y == 0 && z == 0, 0, 0, 0);
        verifyOutwardTriangleWinding(cube);
        ChunkMesh another = ChunkMesher.build((x,y,z) -> x == 0 && y == 0 && z == 0, 0, 0, 0);
        if (!Arrays.equals(cube.vertices(), another.vertices()) ||
                !Arrays.equals(cube.indices(), another.indices())) {
            throw new AssertionError("Mesher output is not deterministic");
        }
        System.out.println("PASS: Asterdium CPU mesher (8 checks)");
    }

    private static void assertQuads(String title, VoxelView view, int expected) {
        assertQuads(title, view, expected, 0, 0, 0);
    }

    private static void assertQuads(String title, VoxelView view, int expected,
                                    int originX, int originY, int originZ) {
        ChunkMesh mesh = ChunkMesher.build(view, originX, originY, originZ);
        if (mesh.quadCount() != expected || mesh.vertexCount() != expected * 4 ||
                mesh.indexCount() != expected * 6) {
            throw new AssertionError(title + ": expected " + expected + " quads, got " + mesh.quadCount());
        }
    }

    private static void verifyOutwardTriangleWinding(ChunkMesh mesh) {
        float[] v = mesh.vertices();
        int[] ix = mesh.indices();
        for (int i = 0; i < ix.length; i += 6) {
            int a = ix[i] * 8;
            int b = ix[i+1] * 8;
            int c = ix[i+2] * 8;
            float abx = v[b] - v[a], aby = v[b+1] - v[a+1], abz = v[b+2] - v[a+2];
            float acx = v[c] - v[a], acy = v[c+1] - v[a+1], acz = v[c+2] - v[a+2];
            float nx = aby*acz-abz*acy;
            float ny = abz*acx-abx*acz;
            float nz = abx*acy-aby*acx;
            float dot = nx*v[a+5] + ny*v[a+6] + nz*v[a+7];
            if (dot <= 0) throw new AssertionError("Quad " + i / 6 + " has inward-facing winding");
        }
    }
}
