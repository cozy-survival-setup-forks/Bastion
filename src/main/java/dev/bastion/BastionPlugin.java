package dev.bastion;

import dev.bastion.boss.Bosses;
import dev.bastion.command.DungeonCommand;
import dev.bastion.door.Doors;
import dev.bastion.dungeon.Artifacts;
import dev.bastion.dungeon.Dialogue;
import dev.bastion.dungeon.Dungeon;
import dev.bastion.dungeon.Messages;
import dev.bastion.dungeon.Rewards;
import dev.bastion.dungeon.Rooms;
import dev.bastion.dungeon.Settings;
import dev.bastion.dungeon.Store;
import dev.bastion.hook.DungeonExpansion;
import dev.bastion.hook.VaultEconomy;
import dev.bastion.listener.DungeonListener;
import dev.bastion.menu.Menus;
import dev.bastion.mob.Mobs;
import dev.bastion.mob.Waves;
import dev.bastion.region.RegionIndex;
import dev.bastion.region.RegionStore;
import dev.bastion.region.Wand;
import dev.bastion.safe.ConfigMigrator;
import dev.bastion.safe.Doctor;
import dev.bastion.safe.FileBackups;
import dev.bastion.safe.Guard;
import dev.bastion.safe.Health;
import dev.bastion.safe.Prep;
import dev.bastion.safe.ServerId;
import dev.bastion.util.Fx;
import dev.bastion.util.TaskBag;
import dev.bastion.util.Titles;
import dev.bastion.world.Points;
import dev.bastion.world.WorldReset;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Bastion: a crowdfunded, instanced dungeon. Money pools to a goal, a join window opens, then a state machine runs
 * three rooms and a boss. Everything about the dungeon (rooms, mobs, bosses, lines, rewards) is in yml files.
 *
 * @author Groovified, Blockie Studios
 */
public final class BastionPlugin extends JavaPlugin {

    private static final int CONFIG_VERSION = 1;
    private static final int LANG_VERSION = 1;

    private final List<Prep.Spec> files = List.of(
            new Prep.Spec("config.yml", "config-version", CONFIG_VERSION, Prep.configMigrator(CONFIG_VERSION), rules -> {
                rules.range("goal", 1, 1_000_000_000_000.0);
                rules.range("backup.interval-hours", 1, 168);
                rules.range("backup.keep", 1, 90);
            }),
            new Prep.Spec("messages.yml", "lang-version", LANG_VERSION, new ConfigMigrator("lang-version", LANG_VERSION), null));

    private BukkitTask backupTask;
    private boolean started;
    private Dungeon dungeon;
    private Menus menus;
    private Wand wand;
    private RegionStore regionStore;
    private Store store;
    private DungeonExpansion expansion;

    @Override
    public void onEnable() {
        try {
            enableInner();
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Bastion failed to start and will be disabled. This is "
                    + "usually a bad or outdated config file - check the config.yml/messages.yml/mobs.yml warnings "
                    + "above this, or delete the whole plugins/Bastion folder to regenerate defaults.", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void enableInner() {
        saveDefaultConfig();
        Health.storage("SQLite bastion.db for funding and return places; YAML for config.yml, messages.yml, mobs.yml, menus.yml and setup.yml");
        Prep.startup(this, files);
        reloadConfig();
        new File(getDataFolder(), "schematics").mkdirs();
        if (getConfig().getConfigurationSection("rooms") == null) {
            getLogger().warning("config.yml has no rooms section, so it is from an older version. Delete config.yml, messages.yml and mobs.yml (and the old dialogue, rooms, bosses, artifacts and rewards files) and restart.");
        }
        Settings settings = new Settings(getConfig());
        Messages messages = new Messages(this);
        messages.load();

        RegionIndex regions = new RegionIndex();
        regionStore = new RegionStore(new File(getDataFolder(), "setup.yml"), regions, getLogger());
        regionStore.load();
        Points points = new Points(new File(getDataFolder(), "setup.yml"), getLogger());
        points.load();
        store = new Store(this);
        try {
            store.open(backupKeep());
        } catch (IOException | SQLException | InvalidConfigurationException e) {
            // the funding and the refunds that are owed are never replaced by an empty set: the plugin stays off
            getLogger().severe("Bastion cannot use its saved data and is switching itself off so no money is lost or paid twice: " + e.getMessage());
            Health.failure("saved data could not be opened: " + e.getMessage());
            store = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        TaskBag tasks = new TaskBag(this);
        Fx fx = new Fx(() -> dungeon.players());

        Doors doors = new Doors(this, new File(getDataFolder(), "setup.yml"), fx, 3);
        doors.load();
        Mobs mobs = new Mobs(this, fx, () -> dungeon.players());
        mobs.load();
        Waves waves = new Waves(this, mobs, points);
        Bosses bosses = new Bosses(this, mobs, points, fx, tasks, () -> dungeon.players());
        bosses.load();
        Artifacts artifacts = new Artifacts(this);
        artifacts.load();
        Rooms rooms = new Rooms(this);
        rooms.load();
        Rewards rewards = new Rewards(this, () -> dungeon.economy, () -> dungeon.settings.coinsCommand, artifacts);
        rewards.journal(store.journal());
        rewards.load();
        Titles titles = new Titles(tasks);
        Dialogue dialogue = new Dialogue(this, () -> dungeon.players(), tasks, titles);
        dialogue.load();
        WorldReset reset = new WorldReset(this, settings.resetBudgetMs);

        dungeon = new Dungeon(this, settings, messages, regions, points, store, rooms, tasks, doors, mobs, waves, bosses,
                artifacts, rewards, dialogue, reset, fx, titles);
        dungeon.editor = new dev.bastion.world.SnapshotEditor(this, new File(getDataFolder(), "schematics/dungeon_clean.schem"));
        dungeon.economy = VaultEconomy.hook();
        if (dungeon.economy == null) getLogger().warning("No Vault economy found: contributions are off. /dungeon forcestart still works.");

        menus = new Menus(this, dungeon);
        menus.load();
        wand = new Wand(messages);

        getServer().getPluginManager().registerEvents(new DungeonListener(dungeon, menus, wand), this);
        getServer().getPluginManager().registerEvents(menus, this);
        var command = getCommand("dungeon");
        if (command != null) {
            DungeonCommand handler = new DungeonCommand(this, dungeon);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            expansion = new DungeonExpansion(dungeon);
            expansion.register();
        }
        dungeon.start();
        boolean beacon = getConfig().getBoolean("metrics.enabled", true);
        Metrics.start(this, ServerId.resolve(getDataFolder().toPath(), store.serverIdSlot(), beacon, getLogger()));
        scheduleBackups();
        started = true;
        Banner.print(this, "Thanks for keeping the gates funded and the dungeon alive.");
        getLogger().info("Bastion ready: " + regions.all().size() + " regions, " + doors.ids().size() + " doors, "
                + mobs.ids().size() + " mobs, " + artifacts.total() + " artifacts.");
    }

    @Override
    public void onDisable() {
        if (menus != null) menus.closeAll();
        if (expansion != null) expansion.unregister();
        if (backupTask != null) backupTask.cancel();
        if (dungeon != null) dungeon.stop();
        if (store != null) store.close();
    }

    private int backupKeep() {
        return Math.max(1, Math.min(90, getConfig().getInt("backup.keep", 7)));
    }

    /** (Re)starts the timer of the database copies from backup.interval-hours and backup.keep. */
    private void scheduleBackups() {
        int hours = Math.max(1, Math.min(168, getConfig().getInt("backup.interval-hours", 6)));
        store.configureBackups(backupKeep());
        if (backupTask != null) backupTask.cancel();
        backupTask = getServer().getScheduler().runTaskTimerAsynchronously(this, store::backup, 20L * 60, hours * 3600L * 20L);
    }

    /** The text of /dungeon doctor. */
    public List<String> doctor() {
        List<String> extra = new ArrayList<>(Prep.versionLines(this, files));
        extra.add("Saved data: bastion.db, schema " + store.schemaVersion() + " (this plugin writes " + Store.SCHEMA + ")");
        extra.add("Funding: " + store.paid().size() + " contribution(s) recorded, return places: " + (store.hasReturns() ? "some" : "none"));
        String newest = store.newestBackup();
        extra.add("Newest database copy on disk: " + (newest == null ? "none yet" : newest));
        extra.add("Pending writes: 0 (every change is written as it happens)");
        extra.add("setup.yml: " + (dev.bastion.util.SetupFile.blocked(new File(getDataFolder(), "setup.yml"))
                ? "could not be read, nothing is saved over it" : "ok"));
        try {
            List<String> unknown = store.journal() == null ? List.of() : store.journal().unknown();
            extra.add("Payouts that may or may not have been made (not repeated): " + unknown.size());
            unknown.forEach(line -> extra.add("  " + line + "   (after checking: /dungeon doctor resolve <id>)"));
        } catch (SQLException e) {
            extra.add("Payout record could not be read: " + e.getMessage());
        }
        return Doctor.report(getName(), getPluginMeta().getVersion(), extra);
    }

    public boolean resolvePayout(String id) {
        try {
            return store.journal() != null && store.journal().resolve(id);
        } catch (SQLException e) {
            getLogger().severe("Could not update the payout record: " + e.getMessage());
            return false;
        }
    }

    /** /dungeon backup now: a checked copy of the database and of the settings and setup files. */
    public boolean backupNow() {
        boolean database = store.backup();
        List<String> names = new ArrayList<>(Prep.fileNames(files));
        names.add("mobs.yml");
        names.add("menus.yml");
        names.add("setup.yml");
        boolean settingsFiles = FileBackups.snapshot(getDataFolder().toPath(), names, 5, getLogger());
        return database && settingsFiles;
    }

    /** Reads every file again. A run in progress keeps going with what it has loaded for the rooms. */
    public boolean reloadAll() {
        if (started) {
            List<Guard.Problem> problems = Prep.validate(this, files);
            if (!problems.isEmpty()) {
                Prep.logRejected(this, problems);
                return false;
            }
        }
        reloadConfig();
        scheduleBackups();
        dungeon.settings = new Settings(getConfig());
        dungeon.messages.load();
        regionStore.load();
        dungeon.points.load();
        dungeon.doors.load();
        dungeon.mobs.load();
        dungeon.bosses.load();
        dungeon.artifacts.load();
        dungeon.rooms.load();
        dungeon.rewards.load();
        dungeon.dialogue.load();
        menus.load();
        return true;
    }

    public Menus menus() {
        return menus;
    }

    public Wand wand() {
        return wand;
    }

    public RegionStore regionStore() {
        return regionStore;
    }
}
