package dev.bastion.dungeon;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** rooms.yml: what each room holds. Waves refer to mobs by id, rooms refer to bosses, doors and points by id. */
public final class Rooms {

    public record WaveDef(String name, Map<String, Integer> mobs) {
    }

    public record RoomDef(String id, int level, String door, String seal, boolean reopen, String core, String spawnGroup, List<WaveDef> waves,
                          String miniboss, String minibossPoint, String guardGroup, Map<String, Integer> guards,
                          String boss, String bossPoint) {
    }

    private final JavaPlugin plugin;
    private final Map<Integer, RoomDef> rooms = new LinkedHashMap<>();

    public Rooms(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        rooms.clear();
        ConfigurationSection all = plugin.getConfig().getConfigurationSection("rooms");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            if (s == null) continue;
            List<WaveDef> waves = new ArrayList<>();
            for (Map<?, ?> wave : s.getMapList("waves")) {
                Map<String, Integer> mobs = new LinkedHashMap<>();
                if (wave.get("mobs") instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> mobs.put(String.valueOf(k), ((Number) v).intValue()));
                }
                waves.add(new WaveDef(String.valueOf(wave.get("name") == null ? "Wave " + (waves.size() + 1) : wave.get("name")), mobs));
            }
            Map<String, Integer> guards = new LinkedHashMap<>();
            ConfigurationSection g = s.getConfigurationSection("guards");
            if (g != null) g.getKeys(false).forEach(k -> guards.put(k, g.getInt(k)));
            RoomDef room = new RoomDef(id, s.getInt("level", 1), s.getString("door"), s.getString("seal"), s.getBoolean("reopen", true), s.getString("core"),
                    s.getString("spawn-group", id), waves, s.getString("miniboss"), s.getString("miniboss-point"),
                    s.getString("guard-group", "guards"), guards, s.getString("boss"), s.getString("boss-point", "boss"));
            rooms.put(room.level(), room);
        }
    }

    public RoomDef room(int level) {
        return rooms.get(level);
    }
}
