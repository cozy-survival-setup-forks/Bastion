package dev.bastion.dungeon;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;

/** config.yml, read once on load and reload. There is no player limit anywhere, minimum or maximum. */
public final class Settings {

    public final String world;
    public final double goal;
    public final long joinWindowMs, lockGapMs, countdownMs, runLimitMs, celebrationMs;
    public final boolean failWhenEveryoneDead, refundWhenNobodyJoins, scaleMobs, topDamageCredit;
    public final double extraMobsPerPlayer;
    public final int maxActiveMobs;
    public final long wavePauseMs, warnIntervalMs;
    public final int artifactsRoom1;
    public final boolean consumeArtifacts, autoDistribute;
    public final List<String> commandWhitelist;
    public final double resetBudgetMs;
    public final String coinsCommand;
    public final int uiTicks;
    public final int hardCapMobs, capPerPlayer;
    public final boolean afkKickEnabled;
    public final long afkTimeoutMs, votekickCooldownMs;

    public Settings(FileConfiguration c) {
        world = c.getString("world", "Dungeons");
        goal = Math.max(1, c.getDouble("goal", 100000));
        joinWindowMs = seconds(c, "join-window-seconds", 300);
        lockGapMs = seconds(c, "lock-gap-seconds", 5);
        countdownMs = seconds(c, "countdown-seconds", 10);
        runLimitMs = seconds(c, "run-limit-minutes", 60) * 60;
        celebrationMs = seconds(c, "celebration-seconds", 120);
        failWhenEveryoneDead = c.getBoolean("fail-when-everyone-dead", true);
        refundWhenNobodyJoins = c.getBoolean("refund-when-nobody-joins", true);
        scaleMobs = c.getBoolean("mobs.scale-with-players", true);
        extraMobsPerPlayer = Math.max(0, c.getDouble("mobs.extra-per-player", 0.5));
        maxActiveMobs = Math.max(1, c.getInt("mobs.max-active", 8));
        wavePauseMs = seconds(c, "mobs.wave-pause-seconds", 5);
        topDamageCredit = c.getString("mobs.credit", "last_hit").toLowerCase(Locale.ROOT).startsWith("top");
        warnIntervalMs = seconds(c, "warn-interval-seconds", 10);
        artifactsRoom1 = Math.max(0, c.getInt("artifacts.room1", 4));
        consumeArtifacts = c.getBoolean("artifacts.consume-on-victory", false);
        autoDistribute = !c.getString("artifacts.room2-mode", "auto").equalsIgnoreCase("drop");
        commandWhitelist = c.getStringList("commands.whitelist").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        resetBudgetMs = Math.max(1, c.getDouble("reset.budget-ms-per-tick", 6));
        coinsCommand = c.getString("rewards.coins-command", "");
        uiTicks = Math.max(1, c.getInt("ui-update-ticks", 5));
        hardCapMobs = Math.max(1, c.getInt("mobs.hard-cap", 40));
        capPerPlayer = Math.max(0, c.getInt("mobs.max-active-per-player", 2));
        afkKickEnabled = c.getBoolean("afk.enabled", true);
        afkTimeoutMs = (long) (Math.max(0, c.getDouble("afk.timeout-minutes", 5)) * 60_000);
        votekickCooldownMs = (long) (Math.max(0, c.getDouble("afk.votekick-cooldown-minutes", 5)) * 60_000);
    }

    private static long seconds(FileConfiguration c, String path, double fallback) {
        return (long) (Math.max(0, c.getDouble(path, fallback)) * 1000);
    }
}
