package dev.bastion.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemFileTest {

    @Test
    void whatIsWrittenIsReadBack(@TempDir Path dir) throws Exception {
        String[] palette = {"minecraft:air", "minecraft:stone", "minecraft:oak_stairs[facing=north,half=bottom]"};
        short[] blocks = new short[4 * 3 * 5];
        for (int i = 0; i < blocks.length; i++) blocks[i] = (short) (i % 3);
        Snapshot in = new Snapshot(4, 3, 5, palette, blocks);
        in.originX = -12;
        in.originY = 64;
        in.originZ = 300;

        Path file = dir.resolve("a.schem");
        SchemFile.write(file, in);
        Snapshot out = SchemFile.read(file);

        assertEquals(4, out.width);
        assertEquals(3, out.height);
        assertEquals(5, out.length);
        assertArrayEquals(palette, out.palette);
        assertArrayEquals(blocks, out.blocks);
        assertEquals(-12, out.originX);
        assertEquals(64, out.originY);
        assertEquals(300, out.originZ);
    }

    @Test
    void aBigPaletteUsesTwoByteNumbers(@TempDir Path dir) throws Exception {
        String[] palette = new String[723];
        for (int i = 0; i < palette.length; i++) palette[i] = "minecraft:test_" + i;
        short[] blocks = new short[10 * 10 * 10];
        for (int i = 0; i < blocks.length; i++) blocks[i] = (short) ((i * 7) % palette.length);
        Snapshot in = new Snapshot(10, 10, 10, palette, blocks);

        Path file = dir.resolve("big.schem");
        SchemFile.write(file, in);
        Snapshot out = SchemFile.read(file);

        assertArrayEquals(blocks, out.blocks);
        assertEquals(723, out.palette.length);
    }

    @Test
    void theRealCastleSchematicReads() throws Exception {
        Path castle = Path.of(System.getProperty("user.home"), "Downloads", "CastleDungeon_1.21.11.schem");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(castle), "the castle schematic is not on this machine");
        Snapshot s = SchemFile.read(castle);
        assertEquals(237, s.width);
        assertEquals(154, s.height);
        assertEquals(192, s.length);
        assertEquals(723, s.palette.length);
        assertEquals("minecraft:air", s.palette[0]);
    }

    @Test
    void indexingMatchesTheSpongeOrder() {
        Snapshot s = new Snapshot(3, 2, 4, new String[]{"a"}, new short[24]);
        assertEquals(0, s.index(0, 0, 0));
        assertEquals(1, s.index(1, 0, 0));
        assertEquals(3, s.index(0, 0, 1));
        assertEquals(12, s.index(0, 1, 0));
        assertEquals(23, s.index(2, 1, 3));
    }
}
