package dev.bastion.dungeon;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What has to survive a restart: the funding so far, who paid what (for refunds), whether a run was in progress, and
 * where every player in the dungeon came from. Files are written off the main thread.
 */
public final class Store {

    public record Return(String world, double x, double y, double z, float yaw, float pitch, GameMode mode) {
        public Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    private final Plugin plugin;
    private final File dataFile;
    private final File returnsFile;
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Bastion-Store");
        t.setDaemon(true);
        return t;
    });

    private double current;
    private boolean runActive;
    private String lastResult = "NONE";
    private String lastBoss = "";
    private final Map<UUID, Double> paid = new LinkedHashMap<>();
    private final Map<UUID, Return> returns = new HashMap<>();

    public Store(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.returnsFile = new File(plugin.getDataFolder(), "locations.yml");
    }

    public void load() {
        if (dataFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
            current = y.getDouble("funding.current");
            runActive = y.getBoolean("run-active");
            lastResult = y.getString("last-result", "NONE");
            lastBoss = y.getString("last-boss", "");
            ConfigurationSection c = y.getConfigurationSection("funding.paid");
            if (c != null) {
                for (String k : c.getKeys(false)) {
                    try {
                        paid.put(UUID.fromString(k), c.getDouble(k));
                    } catch (IllegalArgumentException ignored) {
                        // a hand-edited key that is not an id
                    }
                }
            }
        }
        if (returnsFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(returnsFile);
            for (String k : y.getKeys(false)) {
                ConfigurationSection s = y.getConfigurationSection(k);
                if (s == null) continue;
                try {
                    returns.put(UUID.fromString(k), new Return(s.getString("world"), s.getDouble("x"), s.getDouble("y"),
                            s.getDouble("z"), (float) s.getDouble("yaw"), (float) s.getDouble("pitch"),
                            GameMode.valueOf(s.getString("mode", "SURVIVAL"))));
                } catch (IllegalArgumentException ignored) {
                    // a hand-edited entry
                }
            }
        }
    }

    // ---------------------------------------------------------------- funding

    public double current() {
        return current;
    }

    public void addFunds(UUID who, double amount) {
        current += amount;
        paid.merge(who, amount, Double::sum);
        saveData();
    }

    public void setCurrent(double value) {
        current = value;
        saveData();
    }

    public Map<UUID, Double> paid() {
        return paid;
    }

    /** Forgets the funding, after it has been spent on a run or refunded. */
    public void clearFunding() {
        current = 0;
        paid.clear();
        saveData();
    }

    public boolean runActive() {
        return runActive;
    }

    public void runActive(boolean active) {
        runActive = active;
        saveData();
    }

    public void result(String result, String boss) {
        lastResult = result;
        lastBoss = boss;
        saveData();
    }

    public String lastResult() {
        return lastResult;
    }

    public String lastBoss() {
        return lastBoss;
    }

    // ---------------------------------------------------------------- where players came from

    public void remember(UUID id, Location at, GameMode mode) {
        returns.put(id, new Return(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(), mode));
        saveReturns();
    }

    public Return returnOf(UUID id) {
        return returns.get(id);
    }

    public void forget(UUID id) {
        if (returns.remove(id) != null) saveReturns();
    }

    public boolean hasReturns() {
        return !returns.isEmpty();
    }

    // ---------------------------------------------------------------- writing

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("funding.current", current);
        paid.forEach((id, amount) -> y.set("funding.paid." + id, amount));
        y.set("run-active", runActive);
        y.set("last-result", lastResult);
        y.set("last-boss", lastBoss);
        write(dataFile, y.saveToString());
    }

    private void saveReturns() {
        YamlConfiguration y = new YamlConfiguration();
        returns.forEach((id, r) -> {
            String p = id.toString();
            y.set(p + ".world", r.world);
            y.set(p + ".x", r.x);
            y.set(p + ".y", r.y);
            y.set(p + ".z", r.z);
            y.set(p + ".yaw", r.yaw);
            y.set(p + ".pitch", r.pitch);
            y.set(p + ".mode", r.mode.name());
        });
        write(returnsFile, y.saveToString());
    }

    private void write(File file, String text) {
        Runnable job = () -> {
            try {
                Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save " + file.getName() + ": " + e.getMessage());
            }
        };
        // one writer thread, so two quick saves never land out of order
        writer.execute(job);
    }

    /** Waits for the last writes. Called when the plugin shuts down. */
    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
