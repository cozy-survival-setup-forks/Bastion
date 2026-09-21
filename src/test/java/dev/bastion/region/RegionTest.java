package dev.bastion.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionTest {

    /** An L: a 10x10 block with the top right 5x5 corner cut out. */
    private static PolygonRegion ell() {
        return new PolygonRegion("ell", RegionType.ROOM1, "w",
                new double[]{0, 10, 10, 5, 5, 0}, new double[]{0, 0, 5, 5, 10, 10}, 60, 80);
    }

    @Test
    void cuboidCoversItsBlocksAndNothingElse() {
        CuboidRegion box = CuboidRegion.of("b", RegionType.SPAWN, "w", 5, 10, 5, 0, 0, 0);
        assertTrue(box.contains(0.0, 0, 0));
        assertTrue(box.contains(5.99, 10.99, 5.99));
        assertFalse(box.contains(6.0, 5, 5));
        assertFalse(box.contains(-0.01, 5, 5));
        assertEquals(3.0, box.centerX());
    }

    @Test
    void polygonFollowsItsShape() {
        PolygonRegion l = ell();
        assertTrue(l.contains(2, 70, 2));
        assertTrue(l.contains(8, 70, 2));
        assertTrue(l.contains(2, 70, 8));
        assertFalse(l.contains(8, 70, 8), "the cut-out corner is outside though it is inside the bounding box");
        assertFalse(l.contains(11, 70, 2));
    }

    @Test
    void polygonChecksHeightBeforeAnythingElse() {
        PolygonRegion l = ell();
        assertFalse(l.contains(2, 59.9, 2));
        assertTrue(l.contains(2, 80.9, 2));
        assertFalse(l.contains(2, 81, 2));
    }

    @Test
    void polygonNeedsThreeCorners() {
        assertThrows(IllegalArgumentException.class, () ->
                new PolygonRegion("x", RegionType.ROOM1, "w", new double[]{0, 1}, new double[]{0, 1}, 0, 1));
    }

    @Test
    void indexFindsByChunkAndType() {
        RegionIndex index = new RegionIndex();
        Region far = CuboidRegion.of("far", RegionType.ROOM2, "w", 1000, 0, 1000, 1010, 20, 1010);
        Region room = ell();
        index.put(room);
        index.put(far);
        index.put(CuboidRegion.of("all", RegionType.DUNGEON, "w", -50, 0, -50, 2000, 200, 2000));

        assertSame(room, index.find("w", 2, 70, 2, RegionType.ROOM1));
        assertNull(index.find("w", 8, 70, 8, RegionType.ROOM1));
        assertSame(far, index.findStep("w", 1005, 5, 1005));
        assertNull(index.findStep("w", 500, 5, 500), "the dungeon box is not a step of the run");
        assertNull(index.find("other", 2, 70, 2, RegionType.ROOM1));
        assertTrue(index.inType("w", 500, 5, 500, RegionType.DUNGEON));
    }

    @Test
    void negativeCoordinatesLandInTheRightChunk() {
        RegionIndex index = new RegionIndex();
        Region r = CuboidRegion.of("neg", RegionType.SPAWN, "w", -20, 0, -20, -1, 10, -1);
        index.put(r);
        assertSame(r, index.find("w", -0.5, 5, -0.5, RegionType.SPAWN));
        assertSame(r, index.find("w", -19.5, 5, -19.5, RegionType.SPAWN));
        assertNull(index.find("w", 0.5, 5, 0.5, RegionType.SPAWN));
    }

    @Test
    void removingARegionRemovesItFromTheBuckets() {
        RegionIndex index = new RegionIndex();
        index.put(ell());
        assertTrue(index.remove("ell"));
        assertNull(index.find("w", 2, 70, 2, RegionType.ROOM1));
        assertFalse(index.remove("ell"));
    }
}
