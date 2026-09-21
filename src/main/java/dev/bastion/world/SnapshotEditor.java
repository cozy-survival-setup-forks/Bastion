package dev.bastion.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Keeps dungeon_clean.schem in step with what an admin builds. While the dungeon is idle, each block they place or
 * break inside the dungeon region is remembered, and a few seconds after the last one the changed blocks are written
 * into the saved snapshot, off the main thread. Nobody has to take the snapshot again after a small edit.
 */
public final class SnapshotEditor {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<Long, int[]> dirty = new LinkedHashMap<>();
    private BukkitTask timer;
    private boolean writing;

    public SnapshotEditor(JavaPlugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    /** Remembers a block that changed, and starts the wait before the save. */
    public void mark(Block block, Consumer<String> say) {
        long key = ((long) block.getX() & 0x3FFFFFF) << 38 | ((long) block.getZ() & 0x3FFFFFF) << 12 | (block.getY() & 0xFFF);
        dirty.put(key, new int[]{block.getX(), block.getY(), block.getZ()});
        if (timer != null) timer.cancel();
        String world = block.getWorld().getName();
        timer = Bukkit.getScheduler().runTaskLater(plugin, () -> flush(world, say), 100);
    }

    private void flush(String worldName, Consumer<String> say) {
        timer = null;
        World world = Bukkit.getWorld(worldName);
        if (world == null || dirty.isEmpty()) return;
        if (writing) {
            timer = Bukkit.getScheduler().runTaskLater(plugin, () -> flush(worldName, say), 40);
            return;
        }
        // what the blocks are now, read here on the main thread
        Map<int[], String> now = new HashMap<>();
        for (int[] at : dirty.values()) now.put(at, world.getBlockAt(at[0], at[1], at[2]).getBlockData().getAsString());
        dirty.clear();
        writing = true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String result;
            try {
                result = patch(now);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not update the saved snapshot: " + e);
                result = "snapshot-autosave-failed";
            }
            String message = result;
            Bukkit.getScheduler().runTask(plugin, () -> {
                writing = false;
                say.accept(message);
            });
        });
    }

    private String patch(Map<int[], String> now) throws Exception {
        Snapshot snap = SchemFile.read(file.toPath());
        List<String> palette = new ArrayList<>(Arrays.asList(snap.palette));
        int changed = 0;
        for (Map.Entry<int[], String> e : now.entrySet()) {
            int x = e.getKey()[0] - snap.originX, y = e.getKey()[1] - snap.originY, z = e.getKey()[2] - snap.originZ;
            if (x < 0 || y < 0 || z < 0 || x >= snap.width || y >= snap.height || z >= snap.length) continue;
            int id = palette.indexOf(e.getValue());
            if (id < 0) {
                palette.add(e.getValue());
                id = palette.size() - 1;
            }
            snap.blocks[snap.index(x, y, z)] = (short) id;
            changed++;
        }
        Snapshot out = new Snapshot(snap.width, snap.height, snap.length, palette.toArray(new String[0]), snap.blocks);
        out.originX = snap.originX;
        out.originY = snap.originY;
        out.originZ = snap.originZ;
        SchemFile.write(file.toPath(), out);
        return changed == 0 ? null : "snapshot-autosaved";
    }
}
