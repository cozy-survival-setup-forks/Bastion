package dev.bastion.world;

import dev.bastion.util.Slow;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Puts a world back to a stored snapshot, or takes one, without stalling the server:
 * <ul>
 *   <li>chunks are loaded a few at a time, and each is compared to the snapshot on another thread, so nothing
 *       big runs in a tick;</li>
 *   <li>only blocks that differ are written, and writing stops when the per-tick time budget is used up;</li>
 *   <li>loaded chunks are held with a plugin ticket until the last block is written.</li>
 * </ul>
 * The same code pastes a schematic into an empty world, because that is just a very large difference.
 */
public final class WorldReset {

    public record Result(int chunks, long changed, long millis) {
    }

    private static final int PARALLEL_CHUNKS = 4;

    private final Plugin plugin;
    private final long budgetNanos;
    private volatile boolean busy;

    public WorldReset(Plugin plugin, double budgetMillisPerTick) {
        this.plugin = plugin;
        this.budgetNanos = (long) (budgetMillisPerTick * 1_000_000L);
    }

    public boolean busy() {
        return busy;
    }

    // ---------------------------------------------------------------- restore

    /**
     * @param clean also empty containers and remove dropped items in the area
     * @param progress called on the main thread now and then with a status line, may be null
     */
    public void restore(Snapshot snap, World world, boolean clean, Consumer<Result> done, Consumer<String> progress) {
        if (busy) throw new IllegalStateException("already restoring");
        busy = true;
        long started = System.nanoTime();

        long prepared = Slow.start();
        BlockData[] data = new BlockData[snap.palette.length];
        for (int i = 0; i < data.length; i++) {
            try {
                data[i] = Bukkit.createBlockData(snap.palette[i]);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown block '" + snap.palette[i] + "' in the snapshot, using air");
                data[i] = Bukkit.createBlockData("minecraft:air");
            }
        }

        Slow.check(plugin, "restore: reading the palette", prepared);
        int minCx = snap.originX >> 4, maxCx = (snap.originX + snap.width - 1) >> 4;
        int minCz = snap.originZ >> 4, maxCz = (snap.originZ + snap.length - 1) >> 4;
        List<int[]> chunks = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) chunks.add(new int[]{cx, cz});
        }

        Map<Long, int[]> diffs = new ConcurrentHashMap<>();
        walk(world, chunks, (chunk, cs) -> {
            if (clean) cleanChunk(chunk);
            return CompletableFuture.runAsync(() -> {
                int[] found = diff(snap, data, cs, chunk.getX(), chunk.getZ(), world.getMinHeight(), world.getMaxHeight());
                if (found.length > 0) diffs.put(key(chunk.getX(), chunk.getZ()), found);
            });
        }, progress, "Comparing").whenComplete((v, error) -> onMain(() -> {
            if (error != null) {
                busy = false;
                plugin.getLogger().warning("World restore failed: " + error);
                done.accept(new Result(chunks.size(), -1, (System.nanoTime() - started) / 1_000_000));
                return;
            }
            List<Long> order = new ArrayList<>(diffs.keySet());
            order.sort(Long::compare);
            new Applier(world, snap, data, order, diffs, chunks, started, done, progress).start();
        }));
    }

    private static int[] diff(Snapshot snap, BlockData[] data, ChunkSnapshot cs, int cx, int cz, int worldMin, int worldMax) {
        int x0 = Math.max(snap.originX, cx << 4), x1 = Math.min(snap.originX + snap.width - 1, (cx << 4) + 15);
        int z0 = Math.max(snap.originZ, cz << 4), z1 = Math.min(snap.originZ + snap.length - 1, (cz << 4) + 15);
        int y0 = Math.max(snap.originY, worldMin), y1 = Math.min(snap.originY + snap.height - 1, worldMax - 1);
        int[] out = new int[1024];
        int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                int row = snap.index(x0 - snap.originX, y - snap.originY, z - snap.originZ);
                for (int x = x0; x <= x1; x++) {
                    int index = row + (x - x0);
                    BlockData expected = data[snap.blocks[index]];
                    if (!expected.equals(cs.getBlockData(x & 15, y, z & 15))) {
                        if (n == out.length) out = Arrays.copyOf(out, n * 2);
                        out[n++] = index;
                    }
                }
            }
        }
        return Arrays.copyOf(out, n);
    }

    /** Writes the differences a few thousand blocks at a time, stopping when the tick's time budget is gone. */
    private final class Applier implements Runnable {
        private final World world;
        private final Snapshot snap;
        private final BlockData[] data;
        private final List<Long> order;
        private final Map<Long, int[]> diffs;
        private final List<int[]> chunks;
        private final long started;
        private final Consumer<Result> done;
        private final Consumer<String> progress;
        private final long total;
        private int chunkAt;
        private int indexAt;
        private long written;
        private BukkitTask task;

        Applier(World world, Snapshot snap, BlockData[] data, List<Long> order, Map<Long, int[]> diffs,
                List<int[]> chunks, long started, Consumer<Result> done, Consumer<String> progress) {
            this.world = world;
            this.snap = snap;
            this.data = data;
            this.order = order;
            this.diffs = diffs;
            this.chunks = chunks;
            this.started = started;
            this.done = done;
            this.progress = progress;
            long sum = 0;
            for (int[] d : diffs.values()) sum += d.length;
            this.total = sum;
        }

        void start() {
            if (total == 0) {
                finish();
                return;
            }
            task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
        }

        @Override
        public void run() {
            long began = Slow.start();
            try {
                write();
            } finally {
                Slow.check(plugin, "restore: writing blocks", began);
            }
        }

        private void write() {
            long deadline = System.nanoTime() + budgetNanos;
            int checked = 0;
            while (chunkAt < order.size()) {
                long key = order.get(chunkAt);
                int[] list = diffs.get(key);
                while (indexAt < list.length) {
                    int index = list[indexAt++];
                    int x = snap.originX + index % snap.width;
                    int z = snap.originZ + (index / snap.width) % snap.length;
                    int y = snap.originY + index / (snap.width * snap.length);
                    world.getBlockAt(x, y, z).setBlockData(data[snap.blocks[index]], false);
                    written++;
                    if ((++checked & 63) == 0 && System.nanoTime() > deadline) {
                        return;
                    }
                }
                chunkAt++;
                indexAt = 0;
                diffs.remove(key);
            }
            if (progress != null && written > 0) progress.accept("Wrote " + written + " blocks");
            finish();
        }

        private void finish() {
            if (task != null) task.cancel();
            for (int[] c : chunks) world.removePluginChunkTicket(c[0], c[1], plugin);
            busy = false;
            done.accept(new Result(chunks.size(), written, (System.nanoTime() - started) / 1_000_000));
        }
    }

    /** Only real containers are looked at: asking for every block entity builds a state for each decorated pot and shelf. */
    private static boolean isContainer(Block block) {
        Material type = block.getType();
        return type == Material.BARREL || type == Material.CHEST || type == Material.TRAPPED_CHEST
                || type == Material.HOPPER || type == Material.DROPPER || type == Material.DISPENSER
                || Tag.SHULKER_BOXES.isTagged(type);
    }

    private void cleanChunk(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities(WorldReset::isContainer, false)) {
            if (state instanceof Container container && !container.getInventory().isEmpty()) {
                container.getInventory().clear();
            }
        }
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item) entity.remove();
        }
    }

    // ---------------------------------------------------------------- capture

    /** Takes a snapshot of the box. Coordinates are inclusive and may be given in any order. */
    public void capture(World world, int ax, int ay, int az, int bx, int by, int bz, Consumer<Snapshot> done,
                        Consumer<String> progress) {
        if (busy) throw new IllegalStateException("already busy");
        busy = true;
        int x0 = Math.min(ax, bx), y0 = Math.max(Math.min(ay, by), world.getMinHeight());
        int z0 = Math.min(az, bz), x1 = Math.max(ax, bx), y1 = Math.min(Math.max(ay, by), world.getMaxHeight() - 1);
        int z1 = Math.max(az, bz);
        int width = x1 - x0 + 1, height = y1 - y0 + 1, length = z1 - z0 + 1;
        short[] blocks = new short[Math.multiplyExact(Math.multiplyExact(width, height), length)];

        List<String> names = new ArrayList<>();
        Map<BlockData, Short> known = new ConcurrentHashMap<>();
        List<int[]> chunks = new ArrayList<>();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) chunks.add(new int[]{cx, cz});
        }

        walk(world, chunks, (chunk, cs) -> CompletableFuture.runAsync(() -> {
            int cx = chunk.getX(), cz = chunk.getZ();
            int lx0 = Math.max(x0, cx << 4), lx1 = Math.min(x1, (cx << 4) + 15);
            int lz0 = Math.max(z0, cz << 4), lz1 = Math.min(z1, (cz << 4) + 15);
            Map<BlockData, Short> local = new HashMap<>();
            for (int y = y0; y <= y1; y++) {
                for (int z = lz0; z <= lz1; z++) {
                    int row = ((y - y0) * length + (z - z0)) * width;
                    for (int x = lx0; x <= lx1; x++) {
                        BlockData block = cs.getBlockData(x & 15, y, z & 15);
                        Short id = local.get(block);
                        if (id == null) {
                            synchronized (names) {
                                id = known.get(block);
                                if (id == null) {
                                    id = (short) names.size();
                                    names.add(block.getAsString());
                                    known.put(block, id);
                                }
                            }
                            local.put(block, id);
                        }
                        blocks[row + x - x0] = id;
                    }
                }
            }
        }), progress, "Reading").whenComplete((v, error) -> onMain(() -> {
            for (int[] c : chunks) world.removePluginChunkTicket(c[0], c[1], plugin);
            busy = false;
            if (error != null) {
                plugin.getLogger().warning("Snapshot failed: " + error);
                done.accept(null);
                return;
            }
            Snapshot snap = new Snapshot(width, height, length, names.toArray(new String[0]), blocks);
            snap.originX = x0;
            snap.originY = y0;
            snap.originZ = z0;
            done.accept(snap);
        }));
    }

    // ---------------------------------------------------------------- shared

    interface ChunkStep {
        /** Runs on the main thread with the chunk loaded. The returned future is the part that runs elsewhere. */
        CompletableFuture<?> run(Chunk chunk, ChunkSnapshot snapshot);
    }

    /**
     * Loads the chunks a few at a time, holds each with a ticket, and calls the step for every one. Loading happens
     * off the main thread; the main-thread part of each chunk is queued and done a few at a time, stopping when the
     * tick's time budget is used, so a slow chunk can never stack up with others in one tick.
     */
    private CompletableFuture<Void> walk(World world, List<int[]> chunks, ChunkStep step, Consumer<String> progress, String verb) {
        CompletableFuture<Void> all = new CompletableFuture<>();
        int total = chunks.size();
        if (total == 0) {
            all.complete(null);
            return all;
        }
        java.util.Queue<Chunk> ready = new java.util.concurrent.ConcurrentLinkedQueue<>();
        AtomicInteger requested = new AtomicInteger();
        AtomicInteger loading = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        List<CompletableFuture<?>> pending = java.util.Collections.synchronizedList(new ArrayList<>());

        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (all.isDone()) {
                task[0].cancel();
                return;
            }
            long deadline = System.nanoTime() + budgetNanos;
            Chunk chunk;
            while ((chunk = ready.poll()) != null) {
                long began = Slow.start();
                pending.add(step.run(chunk, chunk.getChunkSnapshot(false, false, false)));
                Slow.check(plugin, "restore: one chunk (" + chunk.getX() + "," + chunk.getZ() + ")", began);
                int n = finished.incrementAndGet();
                if (progress != null && n % 25 == 0) progress.accept(verb + " chunk " + n + " of " + total);
                if (System.nanoTime() > deadline) break;
            }
            // keep a few loads in flight
            while (loading.get() + ready.size() < PARALLEL_CHUNKS && requested.get() < total) {
                int[] c = chunks.get(requested.getAndIncrement());
                loading.incrementAndGet();
                world.addPluginChunkTicket(c[0], c[1], plugin);
                world.getChunkAtAsync(c[0], c[1], true).whenComplete((loaded, error) -> {
                    loading.decrementAndGet();
                    if (error != null || loaded == null) {
                        all.completeExceptionally(error != null ? error : new IllegalStateException("chunk did not load"));
                    } else {
                        ready.add(loaded);
                    }
                });
            }
            if (finished.get() == total) {
                task[0].cancel();
                CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0])).whenComplete((v, e) -> {
                    if (e != null) all.completeExceptionally(e);
                    else all.complete(null);
                });
            }
        }, 1L, 1L);
        return all;
    }

    private void onMain(Runnable run) {
        if (Bukkit.isPrimaryThread()) run.run();
        else Bukkit.getScheduler().runTask(plugin, run);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | ((long) cz & 0xFFFFFFFFL);
    }
}
