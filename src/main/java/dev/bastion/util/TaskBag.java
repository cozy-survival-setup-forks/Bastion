package dev.bastion.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Every task a run starts goes in here, so ending the run cancels all of them. */
public final class TaskBag {

    private final Plugin plugin;
    private final List<BukkitTask> tasks = new ArrayList<>();

    public TaskBag(Plugin plugin) {
        this.plugin = plugin;
    }

    public BukkitTask later(long ticks, Runnable run) {
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            tasks.remove(self[0]);
            try {
                run.run();
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "A dungeon task failed", e);
            }
        }, Math.max(1, ticks));
        tasks.add(self[0]);
        return self[0];
    }

    /** Runs {@code run} every period ticks until it returns false. */
    public BukkitTask every(long period, BooleanSupplier run) {
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            boolean again;
            try {
                again = run.getAsBoolean();
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "A dungeon task failed and was stopped", e);
                again = false;
            }
            if (!again) {
                self[0].cancel();
                tasks.remove(self[0]);
            }
        }, period, period);
        tasks.add(self[0]);
        return self[0];
    }

    public void cancelAll() {
        for (BukkitTask task : List.copyOf(tasks)) task.cancel();
        tasks.clear();
    }

    public int size() {
        tasks.removeIf(BukkitTask::isCancelled);
        return tasks.size();
    }
}
