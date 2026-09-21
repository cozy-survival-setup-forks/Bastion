package dev.bastion.dungeon;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

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

    private record Roll(double chance, List<String> commands) {
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
        var yaml = plugin.getConfig();
        var section = yaml.getConfigurationSection("rewards.tiers");
        if (section != null) {
            for (String tier : section.getKeys(false)) tiers.put(tier, rolls(section.getConfigurationSection(tier)));
        }
        victory = rolls(yaml.getConfigurationSection("rewards.victory"));
    }

    private static List<Roll> rolls(org.bukkit.configuration.ConfigurationSection section) {
        List<Roll> list = new ArrayList<>();
        if (section == null) return list;
        for (String key : section.getKeys(false)) {
            var entry = section.getConfigurationSection(key);
            if (entry == null) continue;
            list.add(new Roll(entry.getDouble("chance", 100), entry.getStringList("commands")));
        }
        return list;
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
        for (String line : roll.commands) {
            String tag = "CONSOLE";
            String rest = line.trim();
            if (rest.startsWith("[") && rest.indexOf(']') > 0) {
                tag = rest.substring(1, rest.indexOf(']')).toUpperCase(java.util.Locale.ROOT);
                rest = rest.substring(rest.indexOf(']') + 1).trim();
            }
            rest = rest.replace("%player%", player.getName());
            switch (tag) {
                case "MONEY" -> {
                    long amount = amount(rest, rng);
                    Economy eco = economy.get();
                    if (amount > 0 && eco != null) {
                        eco.deposit(player, amount);
                        if (announce) player.sendMessage(net.kyori.adventure.text.Component.text("+" + eco.format(amount)));
                    }
                }
                case "COINS" -> {
                    long amount = amount(rest, rng);
                    if (amount > 0) giveCoins(player, amount);
                }
                case "MESSAGE" -> player.sendMessage(dev.bastion.util.Text.component(rest));
                case "PLAYER" -> Bukkit.dispatchCommand(player, rest);
                default -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rest);
            }
        }
    }

    /** "5000" or "5-15". */
    private static long amount(String text, ThreadLocalRandom rng) {
        try {
            String[] p = text.split("-", 2);
            long a = Long.parseLong(p[0].trim());
            long b = p.length > 1 ? Long.parseLong(p[1].trim()) : a;
            return rng.nextLong(Math.min(a, b), Math.max(a, b) + 1);
        } catch (NumberFormatException e) {
            return 0;
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
