package dev.lunivex.asterdium.mesh;

/**
 * Temporary binary visibility model. World coordinates, including coordinates
 * outside the meshed section, must be accepted for cross-section face culling.
 */
@FunctionalInterface
public interface VoxelView {
    boolean isOpaque(int worldX, int worldY, int worldZ);
}
