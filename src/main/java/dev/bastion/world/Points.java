package dev.bastion.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * points.yml: named lists of spots. "anchor" is where players arrive, "chests" are the places an artifact can hide,
 * room1 / room2 are where mobs come out of the ground, "guards" and "boss" are in the throne room.
 */
public final class Points {

    public record Spot(String world, double x, double y, double z, float yaw, float pitch) {

        public Location at() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }

        static Spot read(String text) {
            String[] p = text.trim().split("\\s+");
            return new Spot(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    p.length > 4 ? Float.parseFloat(p[4]) : 0f, p.length > 5 ? Float.parseFloat(p[5]) : 0f);
        }

        String write() {
            return world + " " + x + " " + y + " " + z + " " + yaw + " " + pitch;
        }
    }

    private final File file;
    private final Logger log;
    private final Map<String, List<Spot>> groups = new LinkedHashMap<>();

    public Points(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public void load() {
        groups.clear();
        if (!file.exists()) return;
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file).getConfigurationSection("points");
        if (all == null) return;
        for (String group : all.getKeys(false)) {
            List<Spot> spots = new ArrayList<>();
            for (String line : all.getStringList(group)) {
                try {
                    spots.add(Spot.read(line));
                } catch (RuntimeException e) {
                    log.warning("points.yml: '" + line + "' in " + group + " is not 'world x y z [yaw pitch]'");
                }
            }
            groups.put(group.toLowerCase(java.util.Locale.ROOT), spots);
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        groups.forEach((group, spots) -> yaml.set("points." + group, spots.stream().map(Spot::write).toList()));
        try {
            yaml.save(file);
        } catch (IOException e) {
            log.warning("Could not save points.yml: " + e.getMessage());
        }
    }

    public void add(String group, Location at) {
        groups.computeIfAbsent(group.toLowerCase(java.util.Locale.ROOT), k -> new ArrayList<>()).add(new Spot(
                at.getWorld().getName(), Math.floor(at.getX() * 2) / 2, at.getY(), Math.floor(at.getZ() * 2) / 2,
                at.getYaw(), at.getPitch()));
        save();
    }

    public boolean remove(String group, int number) {
        List<Spot> spots = groups.get(group.toLowerCase(java.util.Locale.ROOT));
        if (spots == null || number < 1 || number > spots.size()) return false;
        spots.remove(number - 1);
        save();
        return true;
    }

    public boolean clear(String group) {
        boolean had = groups.remove(group.toLowerCase(java.util.Locale.ROOT)) != null;
        if (had) save();
        return had;
    }

    public List<Spot> get(String group) {
        return groups.getOrDefault(group.toLowerCase(java.util.Locale.ROOT), List.of());
    }

    public Map<String, List<Spot>> all() {
        return groups;
    }

    public Spot first(String group) {
        List<Spot> spots = get(group);
        return spots.isEmpty() ? null : spots.get(0);
    }

    public Spot random(String group) {
        List<Spot> spots = get(group);
        return spots.isEmpty() ? null : spots.get(ThreadLocalRandom.current().nextInt(spots.size()));
    }
}
