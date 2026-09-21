package dev.bastion.door;

import dev.bastion.util.Fx;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portcullis gates. A door is a box and the blocks that fill it when closed. Opening takes the gate away one
 * horizontal layer at a time from the bottom up, so it visibly lifts, and closing lays it back from the top down.
 * Only a handful of blocks change per step, and one shared task runs while something is moving.
 */
public final class Doors {

    private static final class Door {
        final String id;
        final String world;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        /** layers.get(i) holds the gate blocks at height minY + i. */
        final List<List<Piece>> layers = new ArrayList<>();
        boolean open;

        Door(String id, String world, int[] min, int[] max) {
            this.id = id;
            this.world = world;
            this.minX = min[0];
            this.minY = min[1];
            this.minZ = min[2];
            this.maxX = max[0];
            this.maxY = max[1];
            this.maxZ = max[2];
            for (int y = minY; y <= maxY; y++) layers.add(new ArrayList<>());
        }
    }

    private record Piece(int x, int y, int z, BlockData data) {
    }

    private static final class Motion {
        final Door door;
        final boolean opening;
        int step;

        Motion(Door door, boolean opening) {
            this.door = door;
            this.opening = opening;
        }
    }

    private static final BlockData AIR = Bukkit.createBlockData("minecraft:air");

    private final Plugin plugin;
    private final File file;
    private final Fx fx;
    private final Map<String, Door> doors = new LinkedHashMap<>();
    private final List<Motion> moving = new ArrayList<>();
    private BukkitTask task;
    private final int ticksPerLayer;

    public Doors(Plugin plugin, File file, Fx fx, int ticksPerLayer) {
        this.plugin = plugin;
        this.file = file;
        this.fx = fx;
        this.ticksPerLayer = Math.max(1, ticksPerLayer);
    }

    public void load() {
        doors.clear();
        if (!file.exists()) return;
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file).getConfigurationSection("doors");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            try {
                List<Integer> min = s.getIntegerList("min");
                List<Integer> max = s.getIntegerList("max");
                Door door = new Door(id, s.getString("world"), new int[]{min.get(0), min.get(1), min.get(2)},
                        new int[]{max.get(0), max.get(1), max.get(2)});
                for (String line : s.getStringList("blocks")) {
                    String[] p = line.split(" ", 4);
                    int y = door.minY + Integer.parseInt(p[1]);
                    door.layers.get(y - door.minY).add(new Piece(door.minX + Integer.parseInt(p[0]), y,
                            door.minZ + Integer.parseInt(p[2]), Bukkit.createBlockData(p[3])));
                }
                doors.put(id, door);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("doors.yml: " + id + " is broken (" + e.getMessage() + "), skipping it");
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = dev.bastion.util.SetupFile.open(file, "doors");
        for (Door door : doors.values()) {
            String path = "doors." + door.id;
            yaml.set(path + ".world", door.world);
            yaml.set(path + ".min", List.of(door.minX, door.minY, door.minZ));
            yaml.set(path + ".max", List.of(door.maxX, door.maxY, door.maxZ));
            List<String> blocks = new ArrayList<>();
            for (List<Piece> layer : door.layers) {
                for (Piece p : layer) {
                    blocks.add((p.x - door.minX) + " " + (p.y - door.minY) + " " + (p.z - door.minZ) + " " + p.data.getAsString());
                }
            }
            yaml.set(path + ".blocks", blocks);
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save doors.yml: " + e.getMessage());
        }
    }

    /** Records the blocks in the box as the closed gate. Returns how many blocks that is. */
    public int define(String id, World world, int ax, int ay, int az, int bx, int by, int bz) {
        Door door = new Door(id, world.getName(), new int[]{Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz)},
                new int[]{Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz)});
        int count = 0;
        for (int y = door.minY; y <= door.maxY; y++) {
            for (int x = door.minX; x <= door.maxX; x++) {
                for (int z = door.minZ; z <= door.maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getType().isAir()) {
                        door.layers.get(y - door.minY).add(new Piece(x, y, z, block.getBlockData()));
                        count++;
                    }
                }
            }
        }
        doors.put(id, door);
        save();
        return count;
    }

    public boolean delete(String id) {
        boolean had = doors.remove(id) != null;
        if (had) save();
        return had;
    }

    public Collection<String> ids() {
        return List.copyOf(doors.keySet());
    }

    public boolean exists(String id) {
        return doors.containsKey(id);
    }

    public boolean isOpen(String id) {
        Door door = doors.get(id);
        return door != null && door.open;
    }

    // ---------------------------------------------------------------- movement

    public void open(String id) {
        move(id, true);
    }

    public void close(String id) {
        move(id, false);
    }

    private void move(String id, boolean opening) {
        Door door = doors.get(id);
        if (door == null) {
            plugin.getLogger().warning("No door called " + id + " in doors.yml");
            return;
        }
        moving.removeIf(m -> m.door == door);
        door.open = opening;
        moving.add(new Motion(door, opening));
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::step, ticksPerLayer, ticksPerLayer);
    }

    private void step() {
        long began = dev.bastion.util.Slow.start();
        stepLayers();
        dev.bastion.util.Slow.check(plugin, "door animation step", began);
    }

    private void stepLayers() {
        moving.removeIf(m -> {
            World world = Bukkit.getWorld(m.door.world);
            int layers = m.door.layers.size();
            if (world == null || m.step >= layers) return true;
            // opening lifts from the bottom, closing drops from the top
            int layer = m.opening ? m.step : layers - 1 - m.step;
            List<Piece> pieces = m.door.layers.get(layer);
            for (Piece p : pieces) world.getBlockAt(p.x, p.y, p.z).setBlockData(m.opening ? AIR : p.data, false);
            if (!pieces.isEmpty()) effects(world, m.door, layer, pieces.get(0).data);
            m.step++;
            return m.step >= layers;
        });
        if (moving.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private void effects(World world, Door door, int layer, BlockData data) {
        Location at = new Location(world, (door.minX + door.maxX + 1) / 2.0, door.minY + layer + 0.5, (door.minZ + door.maxZ + 1) / 2.0);
        fx.particle(Particle.BLOCK, at, 12, (door.maxX - door.minX + 1) / 2.0, 0.3, (door.maxZ - door.minZ + 1) / 2.0, 0.05, data);
        if (layer % 2 == 0) fx.sound(Sound.BLOCK_STONE_BREAK, at, 1f, 0.5f);
        if (layer == 0) fx.sound(Sound.BLOCK_IRON_DOOR_OPEN, at, 1f, 0.5f);
    }

    /** Every door back to closed, at once. Used when the dungeon resets. */
    public void closeAllNow() {
        long began = dev.bastion.util.Slow.start();
        closeEverything();
        dev.bastion.util.Slow.check(plugin, "closing every door at once", began);
    }

    private void closeEverything() {
        moving.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Door door : doors.values()) {
            World world = Bukkit.getWorld(door.world);
            if (world == null) continue;
            for (List<Piece> layer : door.layers) {
                for (Piece p : layer) {
                    // most of a closed door is already in place, and writing a block costs more than reading it
                    Block block = world.getBlockAt(p.x, p.y, p.z);
                    if (!block.getBlockData().equals(p.data)) block.setBlockData(p.data, false);
                }
            }
            door.open = false;
        }
    }

    /**
     * Forgets that any door is open or moving, without touching a block. The world restore puts the closed gates
     * back with everything else, and it does so with the chunks loaded, which placing blocks here would not.
     */
    public void markClosed() {
        stop();
        for (Door door : doors.values()) door.open = false;
    }

    public void stop() {
        moving.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
