package dev.bastion.world;

/**
 * A block volume: a palette of block state strings and one palette index per block. The index of a block is
 * (y * length + z) * width + x, counted from the volume's minimum corner, which is the Sponge schematic order.
 */
public final class Snapshot {

    public final int width, height, length;
    public final String[] palette;
    public final short[] blocks;
    /** Where the minimum corner sits in the world it was taken from or is pasted at. */
    public int originX, originY, originZ;

    public Snapshot(int width, int height, int length, String[] palette, short[] blocks) {
        if ((long) width * height * length != blocks.length) {
            throw new IllegalArgumentException("size " + width + "x" + height + "x" + length + " does not match " + blocks.length + " blocks");
        }
        this.width = width;
        this.height = height;
        this.length = length;
        this.palette = palette;
        this.blocks = blocks;
    }

    public int index(int x, int y, int z) {
        return (y * length + z) * width + x;
    }

    public int volume() {
        return blocks.length;
    }
}
