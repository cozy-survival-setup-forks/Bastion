package dev.bastion.region;

import dev.bastion.util.SetupFile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** regions.yml: every region, cuboid or polygon. */
public final class RegionStore {

    private final File file;
    private final RegionIndex index;
    private final Logger log;

    public RegionStore(File file, RegionIndex index, Logger log) {
        this.file = file;
        this.index = index;
        this.log = log;
    }

    public void load() {
        index.clear();
        if (!file.exists()) return;
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file).getConfigurationSection("regions");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            RegionType type = s == null ? null : RegionType.parse(s.getString("type", ""));
            if (type == null || s.getString("world") == null) {
                log.warning("regions.yml: " + id + " has no valid type or world, skipping it");
                continue;
            }
            try {
                index.put("polygon".equalsIgnoreCase(s.getString("shape")) ? polygon(id, type, s) : cuboid(id, type, s));
            } catch (RuntimeException e) {
                log.warning("regions.yml: " + id + " is broken (" + e.getMessage() + "), skipping it");
            }
        }
    }

    private static Region cuboid(String id, RegionType type, ConfigurationSection s) {
        List<Integer> min = s.getIntegerList("min");
        List<Integer> max = s.getIntegerList("max");
        return CuboidRegion.of(id, type, s.getString("world"), min.get(0), min.get(1), min.get(2), max.get(0), max.get(1), max.get(2));
    }

    private static Region polygon(String id, RegionType type, ConfigurationSection s) {
        List<String> points = s.getStringList("points");
        double[] xs = new double[points.size()];
        double[] zs = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            String[] parts = points.get(i).split(",");
            xs[i] = Double.parseDouble(parts[0].trim());
            zs[i] = Double.parseDouble(parts[1].trim());
        }
        return new PolygonRegion(id, type, s.getString("world"), xs, zs, s.getInt("min-y"), s.getInt("max-y"));
    }

    public void save() {
        YamlConfiguration yaml = SetupFile.open(file, "regions");
        for (Region region : index.all()) {
            String path = "regions." + region.id();
            yaml.set(path + ".type", region.type().name());
            yaml.set(path + ".world", region.world());
            if (region instanceof PolygonRegion polygon) {
                yaml.set(path + ".shape", "polygon");
                List<String> points = new ArrayList<>();
                for (int i = 0; i < polygon.xs().length; i++) {
                    points.add(polygon.xs()[i] + "," + polygon.zs()[i]);
                }
                yaml.set(path + ".points", points);
                yaml.set(path + ".min-y", region.minY());
                yaml.set(path + ".max-y", region.maxY());
            } else {
                yaml.set(path + ".shape", "cuboid");
                yaml.set(path + ".min", List.of(region.minX(), region.minY(), region.minZ()));
                yaml.set(path + ".max", List.of(region.maxX(), region.maxY(), region.maxZ()));
            }
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            log.warning("Could not save regions.yml: " + e.getMessage());
        }
    }
}
