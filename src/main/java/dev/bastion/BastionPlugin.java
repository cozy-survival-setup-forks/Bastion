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
import dev.bastion.util.Fx;
import dev.bastion.util.TaskBag;
import dev.bastion.world.Points;
import dev.bastion.world.WorldReset;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Bastion: a crowdfunded, instanced dungeon. Money pools to a goal, a join window opens, then a state machine runs
 * three rooms and a boss. Everything about the dungeon (rooms, mobs, bosses, lines, rewards) is in yml files.
 */
public final class BastionPlugin extends JavaPlugin {

    private Dungeon dungeon;
    private Menus menus;
    private Wand wand;
    private RegionStore regionStore;
    private Store store;
    private DungeonExpansion expansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Settings settings = new Settings(getConfig());
        Messages messages = new Messages(this);
        messages.load();

        RegionIndex regions = new RegionIndex();
        regionStore = new RegionStore(new File(getDataFolder(), "regions.yml"), regions, getLogger());
        regionStore.load();
        Points points = new Points(new File(getDataFolder(), "points.yml"), getLogger());
        points.load();
        store = new Store(this);
        store.load();

        TaskBag tasks = new TaskBag(this);
        Fx fx = new Fx(() -> dungeon.players());

        Doors doors = new Doors(this, new File(getDataFolder(), "doors.yml"), fx, 3);
        doors.load();
        Mobs mobs = new Mobs(this, fx, () -> dungeon.players());
        mobs.load();
        Waves waves = new Waves(mobs, points);
        Bosses bosses = new Bosses(this, mobs, points, fx, tasks, () -> dungeon.players());
        bosses.load();
        Artifacts artifacts = new Artifacts(this);
        artifacts.load();
        Rooms rooms = new Rooms(this);
        rooms.load();
        Rewards rewards = new Rewards(this, () -> dungeon.economy, () -> dungeon.settings.coinsCommand);
        rewards.load();
        Dialogue dialogue = new Dialogue(this, () -> dungeon.players(), tasks);
        dialogue.load();
        WorldReset reset = new WorldReset(this, settings.resetBudgetMs);

        dungeon = new Dungeon(this, settings, messages, regions, points, store, rooms, tasks, doors, mobs, waves, bosses,
                artifacts, rewards, dialogue, reset, fx);
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
        getLogger().info("Bastion ready: " + regions.all().size() + " regions, " + doors.ids().size() + " doors, "
                + mobs.ids().size() + " mobs, " + artifacts.total() + " artifacts.");
    }

    @Override
    public void onDisable() {
        if (menus != null) menus.closeAll();
        if (expansion != null) expansion.unregister();
        if (dungeon != null) dungeon.stop();
        if (store != null) store.close();
    }

    /** Reads every file again. A run in progress keeps going with what it has loaded for the rooms. */
    public void reloadAll() {
        reloadConfig();
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
