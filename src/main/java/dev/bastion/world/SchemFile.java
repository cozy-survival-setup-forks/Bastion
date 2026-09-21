package dev.bastion.world;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Reads Sponge schematics (versions 2 and 3, what WorldEdit and FAWE save) and writes version 3. Only block states
 * are kept: block entity data (sign text, container items) is not read, since a dungeon reset empties containers anyway.
 */
public final class SchemFile {

    private SchemFile() {
    }

    // ---------------------------------------------------------------- reading

    public static Snapshot read(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return read(in);
        }
    }

    @SuppressWarnings("unchecked")
    public static Snapshot read(InputStream gzipped) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(gzipped, 1 << 16));
        if (in.readByte() != 10) throw new IOException("not an NBT file");
        in.readUTF();
        Map<String, Object> root = (Map<String, Object>) readTag(in, (byte) 10);
        Map<String, Object> schem = root.get("Schematic") instanceof Map<?, ?> m ? (Map<String, Object>) m : root;

        int width = ((Number) schem.get("Width")).intValue() & 0xFFFF;
        int height = ((Number) schem.get("Height")).intValue() & 0xFFFF;
        int length = ((Number) schem.get("Length")).intValue() & 0xFFFF;

        Map<String, Object> paletteTag;
        byte[] data;
        if (schem.get("Blocks") instanceof Map<?, ?> blocks) {        // version 3
            paletteTag = (Map<String, Object>) blocks.get("Palette");
            data = (byte[]) blocks.get("Data");
        } else {                                                        // version 2
            paletteTag = (Map<String, Object>) schem.get("Palette");
            data = (byte[]) schem.get("BlockData");
        }
        if (paletteTag == null || data == null) throw new IOException("the schematic has no block data");

        String[] palette = new String[paletteTag.size()];
        for (Map.Entry<String, Object> e : paletteTag.entrySet()) {
            int i = ((Number) e.getValue()).intValue();
            if (i < 0 || i >= palette.length) throw new IOException("bad palette index " + i);
            palette[i] = e.getKey();
        }

        short[] blocks = new short[Math.multiplyExact(Math.multiplyExact(width, height), length)];
        int position = 0;
        for (int i = 0; i < blocks.length; i++) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (position >= data.length) throw new IOException("the block data ends early");
                byte b = data[position++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            blocks[i] = (short) value;
        }

        Snapshot snapshot = new Snapshot(width, height, length, palette, blocks);
        if (schem.get("Metadata") instanceof Map<?, ?> meta && meta.get("BastionOrigin") instanceof int[] o && o.length == 3) {
            snapshot.originX = o[0];
            snapshot.originY = o[1];
            snapshot.originZ = o[2];
        }
        return snapshot;
    }

    private static Object readTag(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case 1:
                return in.readByte();
            case 2:
                return in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                return bytes;
            }
            case 8:
                return in.readUTF();
            case 9: {
                byte itemType = in.readByte();
                int size = in.readInt();
                List<Object> list = new ArrayList<>(Math.min(size, 1024));
                for (int i = 0; i < size; i++) list.add(readTag(in, itemType));
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    byte t = in.readByte();
                    if (t == 0) return map;
                    map.put(in.readUTF(), readTag(in, t));
                }
            }
            case 11: {
                int[] ints = new int[in.readInt()];
                for (int i = 0; i < ints.length; i++) ints[i] = in.readInt();
                return ints;
            }
            case 12: {
                long[] longs = new long[in.readInt()];
                for (int i = 0; i < longs.length; i++) longs[i] = in.readLong();
                return longs;
            }
            default:
                throw new IOException("unknown tag type " + type);
        }
    }

    // ---------------------------------------------------------------- writing

    public static void write(Path file, Snapshot snapshot) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file), 1 << 16)) {
            write(out, snapshot);
        }
    }

    public static void write(OutputStream gzipped, Snapshot s) throws IOException {
        DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(gzipped, 1 << 16));
        out.writeByte(10);
        out.writeUTF("");
        beginCompound(out, "Schematic");
        namedInt(out, "Version", 3);
        namedInt(out, "DataVersion", 4671);
        namedShort(out, "Width", s.width);
        namedShort(out, "Height", s.height);
        namedShort(out, "Length", s.length);
        namedIntArray(out, "Offset", new int[]{0, 0, 0});

        beginCompound(out, "Metadata");
        namedIntArray(out, "BastionOrigin", new int[]{s.originX, s.originY, s.originZ});
        out.writeByte(0);

        beginCompound(out, "Blocks");
        beginCompound(out, "Palette");
        for (int i = 0; i < s.palette.length; i++) namedInt(out, s.palette[i], i);
        out.writeByte(0);

        ByteArrayOutputStream data = new ByteArrayOutputStream(s.blocks.length);
        for (short block : s.blocks) {
            int v = block & 0xFFFF;
            while ((v & -128) != 0) {
                data.write(v & 127 | 128);
                v >>>= 7;
            }
            data.write(v);
        }
        out.writeByte(7);
        out.writeUTF("Data");
        out.writeInt(data.size());
        data.writeTo(out);

        out.writeByte(9);
        out.writeUTF("BlockEntities");
        out.writeByte(10);
        out.writeInt(0);
        out.writeByte(0);   // end of Blocks
        out.writeByte(0);   // end of Schematic
        out.writeByte(0);   // end of root
        out.flush();
        // ponytail: GZIPOutputStream is finished by closing the stream the caller opened
        if (gzipped instanceof GZIPOutputStream g) g.finish();
    }

    private static void beginCompound(DataOutputStream out, String name) throws IOException {
        out.writeByte(10);
        out.writeUTF(name);
    }

    private static void namedInt(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(3);
        out.writeUTF(name);
        out.writeInt(value);
    }

    private static void namedShort(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(2);
        out.writeUTF(name);
        out.writeShort(value);
    }

    private static void namedIntArray(DataOutputStream out, String name, int[] values) throws IOException {
        out.writeByte(11);
        out.writeUTF(name);
        out.writeInt(values.length);
        for (int v : values) out.writeInt(v);
    }
}
