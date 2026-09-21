package dev.bastion.region;

/** A volume in one world. Bounds are inclusive block coordinates. */
public sealed interface Region permits CuboidRegion, PolygonRegion {

    String id();

    RegionType type();

    String world();

    int minX();

    int minY();

    int minZ();

    int maxX();

    int maxY();

    int maxZ();

    /** The exact test. Callers go through {@link RegionIndex}, which has already rejected by chunk. */
    boolean contains(double x, double y, double z);

    default double centerX() {
        return (minX() + maxX() + 1) / 2.0;
    }

    default double centerZ() {
        return (minZ() + maxZ() + 1) / 2.0;
    }
}
