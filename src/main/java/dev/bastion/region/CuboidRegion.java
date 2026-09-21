package dev.bastion.region;

public record CuboidRegion(String id, RegionType type, String world, int minX, int minY, int minZ,
                           int maxX, int maxY, int maxZ) implements Region {

    public static CuboidRegion of(String id, RegionType type, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new CuboidRegion(id, type, world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    @Override
    public boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
    }
}
