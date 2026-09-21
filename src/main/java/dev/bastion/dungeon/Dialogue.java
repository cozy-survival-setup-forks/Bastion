package dev.bastion.dungeon;

import dev.bastion.util.TaskBag;
import dev.bastion.util.Titles;
import dev.bastion.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * messages.yml: every scripted line, keyed by what happened. NORMAL lines show on the action bar and in dungeon chat,
 * MAJOR ones add a title on top. Everything stays inside the run: nothing goes to global chat or screens.
 */
public final class Dialogue {

    public enum Trigger {
        ON_ENTER_DUNGEON, ON_ROOM1_UNLOCK, ON_WAVE_START, ON_ARTIFACT_FOUND, ON_ROOM2_UNLOCK, ON_MINIBOSS_SPAWN,
        ON_ROOM3_UNLOCK, ON_BOSS_SPAWN, ON_BOSS_LOW_HP, ON_VICTORY, ON_FAILURE, ON_MOB_KILL, ON_PLAYER_HURT_BY_MOB,
        ON_PLAYER_DEATH
    }

    private record Entry(boolean major, boolean actionBarOnly, boolean sequential, List<String> lines, Sound sound,
                         int delayTicks, long cooldownMs, double chance, String title, String color) {
    }

    private final JavaPlugin plugin;
    private final Supplier<Collection<Player>> audience;
    private final TaskBag tasks;
    private final Titles titles;
    private final Map<Trigger, Entry> entries = new EnumMap<>(Trigger.class);
    private final Map<Trigger, Integer> cursor = new EnumMap<>(Trigger.class);
    private final Map<Trigger, Map<UUID, Long>> cooldowns = new EnumMap<>(Trigger.class);
    private String chatPrefix = "";

    public Dialogue(JavaPlugin plugin, Supplier<Collection<Player>> audience, TaskBag tasks, Titles titles) {
        this.plugin = plugin;
        this.audience = audience;
        this.tasks = tasks;
        this.titles = titles;
    }

    public void load() {
        entries.clear();
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        chatPrefix = yaml.getString("chat-prefix", "");
        ConfigurationSection all = yaml.getConfigurationSection("dialogue");
        if (all == null) return;
        for (String key : all.getKeys(false)) {
            Trigger trigger;
            try {
                trigger = Trigger.valueOf(key.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("messages.yml: unknown trigger " + key);
                continue;
            }
            ConfigurationSection s = all.getConfigurationSection(key);
            if (s == null || s.getStringList("lines").isEmpty()) continue;
            Sound sound = null;
            String soundKey = s.getString("sound");
            if (soundKey != null) {
                NamespacedKey k = NamespacedKey.fromString(soundKey.toLowerCase(Locale.ROOT));
                sound = k == null ? null : Registry.SOUNDS.get(k);
            }
            entries.put(trigger, new Entry(
                    s.getString("tier", "NORMAL").equalsIgnoreCase("MAJOR"),
                    s.getBoolean("actionbar-only", false),
                    s.getString("pick", "random").equalsIgnoreCase("sequential"),
                    s.getStringList("lines"), sound, s.getInt("delay-ticks", 0),
                    (long) (s.getDouble("cooldown-seconds", 0) * 1000), s.getDouble("chance", 100),
                    s.getString("title", ""), s.getString("color", "#FFFFFF")));
        }
    }

    /** To everyone in the run. */
    public void fire(Trigger trigger, Map<String, String> ctx) {
        deliver(trigger, null, null, ctx);
    }

    /** To one player, with the trigger's own cooldown and chance applied to that player. */
    public void fireTo(Trigger trigger, Player player, Map<String, String> ctx) {
        deliver(trigger, player, null, ctx);
    }

    /** To everyone except one player, for things like a death, which the others see and the player does not. */
    public void fireExcept(Trigger trigger, Player except, Map<String, String> ctx) {
        deliver(trigger, null, except, ctx);
    }

    private void deliver(Trigger trigger, Player only, Player except, Map<String, String> ctx) {
        Entry entry = entries.get(trigger);
        if (entry == null) return;
        if (entry.chance < 100 && ThreadLocalRandom.current().nextDouble() * 100 >= entry.chance) return;

        long now = System.currentTimeMillis();
        List<Player> targets = new ArrayList<>();
        if (only != null) {
            targets.add(only);
        } else {
            for (Player p : audience.get()) if (p != except) targets.add(p);
        }
        if (entry.cooldownMs > 0) {
            Map<UUID, Long> cd = cooldowns.computeIfAbsent(trigger, k -> new HashMap<>());
            targets.removeIf(p -> cd.getOrDefault(p.getUniqueId(), 0L) > now);
            for (Player p : targets) cd.put(p.getUniqueId(), now + entry.cooldownMs);
        }
        if (targets.isEmpty()) return;

        String line = pick(trigger, entry);
        for (Map.Entry<String, String> e : ctx.entrySet()) line = line.replace("%" + e.getKey() + "%", e.getValue());
        Component text = Text.component(line);
        Component chat = Text.component(chatPrefix + line);
        String heading = entry.title;
        for (Map.Entry<String, String> e : ctx.entrySet()) heading = heading.replace("%" + e.getKey() + "%", e.getValue());
        String plainLine = line;

        String shownHeading = heading;
        Runnable send = () -> {
            if (entry.major && !shownHeading.isEmpty()) titles.type(targets, shownHeading, entry.color, plainLine);
            for (Player p : targets) {
                if (!p.isOnline()) continue;
                p.sendActionBar(text);
                if (!entry.actionBarOnly) p.sendMessage(chat);
                if (entry.sound != null) p.playSound(p.getLocation(), entry.sound, 0.7f, 1f);
            }
        };
        if (entry.delayTicks > 0) tasks.later(entry.delayTicks, send);
        else send.run();
    }

    private String pick(Trigger trigger, Entry entry) {
        List<String> lines = entry.lines;
        if (entry.sequential) {
            int i = cursor.merge(trigger, 1, Integer::sum) - 1;
            return lines.get(i % lines.size());
        }
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    public void resetCooldowns() {
        cooldowns.clear();
        cursor.clear();
    }
}
