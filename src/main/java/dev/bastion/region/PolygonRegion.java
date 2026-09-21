package dev.bastion.region;

/**
 * An irregular footprint: vertices in x and z, and a height range. The vertices are block corners, so a square
 * from (0,0) to (10,10) covers blocks 0 to 9.
 */
public final class PolygonRegion implements Region {

    private final String id;
    private final RegionType type;
    private final String world;
    private final double[] xs;
    private final double[] zs;
    private final int minX, minY, minZ, maxX, maxY, maxZ;

    public PolygonRegion(String id, RegionType type, String world, double[] xs, double[] zs, int minY, int maxY) {
        if (xs.length != zs.length || xs.length < 3) {
            throw new IllegalArgumentException("a polygon needs at least 3 vertices");
        }
        this.id = id;
        this.type = type;
        this.world = world;
        this.xs = xs.clone();
        this.zs = zs.clone();
        double loX = Double.MAX_VALUE, loZ = Double.MAX_VALUE, hiX = -Double.MAX_VALUE, hiZ = -Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            loX = Math.min(loX, xs[i]);
            hiX = Math.max(hiX, xs[i]);
            loZ = Math.min(loZ, zs[i]);
            hiZ = Math.max(hiZ, zs[i]);
        }
        this.minX = (int) Math.floor(loX);
        this.maxX = (int) Math.ceil(hiX) - 1;
        this.minZ = (int) Math.floor(loZ);
        this.maxZ = (int) Math.ceil(hiZ) - 1;
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
    }

    public double[] xs() {
        return xs.clone();
    }

    public double[] zs() {
        return zs.clone();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public RegionType type() {
        return type;
    }

    @Override
    public String world() {
        return world;
    }

    @Override
    public int minX() {
        return minX;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int minZ() {
        return minZ;
    }

    @Override
    public int maxX() {
        return maxX;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Override
    public int maxZ() {
        return maxZ;
    }

    @Override
    public boolean contains(double x, double y, double z) {
        // cheap rejections first: height, then the bounding box, and only then the ray cast
        if (y < minY || y >= maxY + 1 || x < minX || x >= maxX + 1 || z < minZ || z >= maxZ + 1) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            if ((zs[i] > z) != (zs[j] > z) && x < (xs[j] - xs[i]) * (z - zs[i]) / (zs[j] - zs[i]) + xs[i]) {
                inside = !inside;
            }
        }
        return inside;
    }
}
