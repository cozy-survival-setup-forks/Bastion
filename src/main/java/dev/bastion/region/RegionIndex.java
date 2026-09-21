package dev.bastion.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Regions bucketed by chunk, so a lookup only tests the few regions that touch the chunk the point is in. Lookups
 * allocate nothing. Rebuilt whenever a region is added or removed, which is rare.
 */
public final class RegionIndex {

    private static final Region[] NONE = new Region[0];

    private final Map<String, Region> byId = new LinkedHashMap<>();
    private volatile Map<String, Map<Long, Region[]>> buckets = Map.of();

    public synchronized void put(Region region) {
        byId.put(region.id(), region);
        rebuild();
    }

    public synchronized boolean remove(String id) {
        boolean removed = byId.remove(id) != null;
        if (removed) rebuild();
        return removed;
    }

    public synchronized void clear() {
        byId.clear();
        rebuild();
    }

    public Region get(String id) {
        return byId.get(id);
    }

    public Collection<Region> all() {
        return List.copyOf(byId.values());
    }

    public List<Region> ofType(RegionType type) {
        List<Region> found = new ArrayList<>();
        for (Region region : byId.values()) {
            if (region.type() == type) found.add(region);
        }
        return found;
    }

    public Region firstOfType(RegionType type) {
        for (Region region : byId.values()) {
            if (region.type() == type) return region;
        }
        return null;
    }

    private void rebuild() {
        Map<String, Map<Long, List<Region>>> building = new HashMap<>();
        for (Region region : byId.values()) {
            Map<Long, List<Region>> world = building.computeIfAbsent(region.world(), k -> new HashMap<>());
            for (int cx = region.minX() >> 4; cx <= region.maxX() >> 4; cx++) {
                for (int cz = region.minZ() >> 4; cz <= region.maxZ() >> 4; cz++) {
                    world.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(region);
                }
            }
        }
        Map<String, Map<Long, Region[]>> done = new HashMap<>();
        building.forEach((world, chunks) -> {
            Map<Long, Region[]> frozen = new HashMap<>();
            chunks.forEach((chunk, list) -> frozen.put(chunk, list.toArray(NONE)));
            done.put(world, frozen);
        });
        buckets = done;
    }

    private static long key(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    private Region[] bucket(String world, double x, double z) {
        Map<Long, Region[]> chunks = buckets.get(world);
        if (chunks == null) return NONE;
        Region[] found = chunks.get(key(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4));
        return found == null ? NONE : found;
    }

    /** The first region of this type that contains the point, or null. */
    public Region find(String world, double x, double y, double z, RegionType type) {
        for (Region region : bucket(world, x, z)) {
            if (region.type() == type && region.contains(x, y, z)) return region;
        }
        return null;
    }

    /** The first region with a level (spawn or a room) that contains the point, or null. */
    public Region findStep(String world, double x, double y, double z) {
        for (Region region : bucket(world, x, z)) {
            if (region.type().level() >= 0 && region.contains(x, y, z)) return region;
        }
        return null;
    }

    public boolean inType(String world, double x, double y, double z, RegionType type) {
        return find(world, x, y, z, type) != null;
    }
}
