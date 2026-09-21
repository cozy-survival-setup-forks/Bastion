package dev.bastion.dungeon;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * rewards.yml: a list of chance rolls per mob tier, and one for the victory. Each roll can pay money (Vault),
 * coins (kept on the player, or paid through a command if the server already has a points plugin) and run commands.
 */
public final class Rewards {

    /** How money moves. Null when no economy plugin is there. */
    public interface Economy {
        boolean has(org.bukkit.OfflinePlayer player, double amount);

        boolean withdraw(org.bukkit.OfflinePlayer player, double amount);

        void deposit(org.bukkit.OfflinePlayer player, double amount);

        String format(double amount);
    }

    private record Roll(double chance, long[] money, long[] coins, List<String> commands) {
    }

    public static final NamespacedKey COINS = new NamespacedKey("bastion", "coins");

    private final JavaPlugin plugin;
    private final Supplier<Economy> economy;
    private final Supplier<String> coinsCommand;
    private final Map<String, List<Roll>> tiers = new HashMap<>();
    private List<Roll> victory = List.of();

    public Rewards(JavaPlugin plugin, Supplier<Economy> economy, Supplier<String> coinsCommand) {
        this.plugin = plugin;
        this.economy = economy;
        this.coinsCommand = coinsCommand;
    }

    public void load() {
        tiers.clear();
        File file = new File(plugin.getDataFolder(), "rewards.yml");
        if (!file.exists()) plugin.saveResource("rewards.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var section = yaml.getConfigurationSection("tiers");
        if (section != null) {
            for (String tier : section.getKeys(false)) tiers.put(tier, rolls(section.getMapList(tier)));
        }
        victory = rolls(yaml.getMapList("victory"));
    }

    private static List<Roll> rolls(List<Map<?, ?>> maps) {
        List<Roll> list = new ArrayList<>();
        for (Map<?, ?> m : maps) {
            List<String> commands = new ArrayList<>();
            if (m.get("commands") instanceof List<?> l) l.forEach(o -> commands.add(String.valueOf(o)));
            list.add(new Roll(number(m.get("chance"), 100), range(m.get("money")), range(m.get("coins")), commands));
        }
        return list;
    }

    private static double number(Object o, double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }

    private static long[] range(Object o) {
        if (o instanceof Number n) return new long[]{n.longValue(), n.longValue()};
        if (o instanceof List<?> l && l.size() >= 2 && l.get(0) instanceof Number a && l.get(1) instanceof Number b) {
            return new long[]{a.longValue(), Math.max(a.longValue(), b.longValue())};
        }
        return null;
    }

    public void kill(Player killer, String tier) {
        List<Roll> rolls = tiers.get(tier);
        if (killer == null || rolls == null) return;
        rolls.forEach(r -> roll(killer, r, false));
    }

    public void victory(Player player) {
        victory.forEach(r -> roll(player, r, true));
    }

    private void roll(Player player, Roll roll, boolean announce) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (roll.chance < 100 && rng.nextDouble() * 100 >= roll.chance) return;
        if (roll.money != null) {
            long amount = rng.nextLong(roll.money[0], roll.money[1] + 1);
            Economy eco = economy.get();
            if (amount > 0 && eco != null) {
                eco.deposit(player, amount);
                if (announce) player.sendMessage(net.kyori.adventure.text.Component.text("+" + eco.format(amount)));
            }
        }
        if (roll.coins != null) {
            long amount = rng.nextLong(roll.coins[0], roll.coins[1] + 1);
            if (amount > 0) giveCoins(player, amount);
        }
        for (String command : roll.commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
        }
    }

    public void giveCoins(Player player, long amount) {
        String command = coinsCommand.get();
        if (command != null && !command.isBlank()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()).replace("%amount%", String.valueOf(amount)));
            return;
        }
        player.getPersistentDataContainer().set(COINS, PersistentDataType.LONG, coins(player) + amount);
    }

    public static long coins(Player player) {
        return player.getPersistentDataContainer().getOrDefault(COINS, PersistentDataType.LONG, 0L);
    }
}
