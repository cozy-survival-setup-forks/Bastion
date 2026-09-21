package dev.bastion.dungeon;

import dev.bastion.boss.Bosses;
import dev.bastion.dungeon.Dialogue.Trigger;
import dev.bastion.dungeon.Rooms.RoomDef;
import dev.bastion.door.Doors;
import dev.bastion.mob.Mobs;
import dev.bastion.mob.Waves;
import dev.bastion.region.Region;
import dev.bastion.region.RegionIndex;
import dev.bastion.region.RegionType;
import dev.bastion.util.Fx;
import dev.bastion.util.Slow;
import dev.bastion.util.TaskBag;
import dev.bastion.util.Text;
import dev.bastion.util.Titles;
import dev.bastion.world.Points;
import dev.bastion.world.SchemFile;
import dev.bastion.world.Snapshot;
import dev.bastion.world.WorldReset;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The state machine. One enum says where the dungeon is; this class moves it along, on one shared tick, and everything
 * else (menus, doors, mobs, commands) asks it. There is no player limit: any number can join, and a forced start needs
 * no goal reached and no minimum.
 */
public final class Dungeon {

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final String[] COMPASS = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};

    // ---- collaborators, public so listeners and commands can use them
    public final JavaPlugin plugin;
    public Settings settings;
    public final Messages messages;
    public final RegionIndex regions;
    public final Points points;
    public final Store store;
    public final Rooms rooms;
    public final TaskBag tasks;
    public final Fx fx;
    private final Titles titles;
    public final Doors doors;
    public final Mobs mobs;
    public final Waves waves;
    public final Bosses bosses;
    public final Artifacts artifacts;
    public final Rewards rewards;
    public final Dialogue dialogue;
    public final WorldReset reset;
    public Rewards.Economy economy;

    // ---- state
    private DungeonState state = DungeonState.FUNDING;
    private final Map<UUID, Run> runs = new LinkedHashMap<>();
    private List<Player> cache = List.of();
    private boolean cacheDirty = true;
    private long joinDeadline, lockDeadline, countdownDeadline, runDeadline, celebrationDeadline;
    private long nextSecond, nextCompass, nextFirework;
    private int shownCountdown;
    private boolean minibossDead;
    private String lastHit = "";
    private String bossName = "";
    private BukkitTask loop;

    public Dungeon(JavaPlugin plugin, Settings settings, Messages messages, RegionIndex regions, Points points,
                   Store store, Rooms rooms, TaskBag tasks, Doors doors, Mobs mobs, Waves waves, Bosses bosses,
                   Artifacts artifacts, Rewards rewards, Dialogue dialogue, WorldReset reset, Fx fx, Titles titles) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.regions = regions;
        this.points = points;
        this.store = store;
        this.rooms = rooms;
        this.tasks = tasks;
        this.doors = doors;
        this.mobs = mobs;
        this.waves = waves;
        this.bosses = bosses;
        this.artifacts = artifacts;
        this.rewards = rewards;
        this.dialogue = dialogue;
        this.reset = reset;
        this.fx = fx;
        this.titles = titles;
        artifacts.onFound(this::artifactFound);
    }

    public void start() {
        loop = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, settings.uiTicks, settings.uiTicks);
        // a run that was cut off by a crash or restart leaves the world dirty and players stranded
        if (store.runActive() || store.hasReturns()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                plugin.getLogger().warning("A dungeon run was cut off. Putting everything back.");
                beginReset();
            }, 40L);
        }
    }

    public void stop() {
        if (loop != null) loop.cancel();
        tasks.cancelAll();
        waves.stop();
        mobs.killAll();
        bosses.stopAll();
        doors.stop();
    }

    // ---------------------------------------------------------------- queries

    public DungeonState state() {
        return state;
    }

    public Run run(UUID id) {
        return runs.get(id);
    }

    public boolean isInside(UUID id) {
        Run run = runs.get(id);
        return run != null && run.inside();
    }

    public Map<UUID, Run> runs() {
        return runs;
    }

    /** The players who are in the run and not out of it, online. Cached, and rebuilt when the party changes. */
    public Collection<Player> players() {
        if (cacheDirty) {
            List<Player> list = new ArrayList<>(runs.size());
            for (Run run : runs.values()) {
                if (!run.inside()) continue;
                Player p = Bukkit.getPlayer(run.id);
                if (p != null) list.add(p);
            }
            cache = Collections.unmodifiableList(list);
            cacheDirty = false;
        }
        return cache;
    }

    public void partyChanged() {
        cacheDirty = true;
    }

    public int insideCount() {
        int n = 0;
        for (Run r : runs.values()) if (r.inside()) n++;
        return n;
    }

    public double funding() {
        return store.current();
    }

    public long msLeft(long deadline) {
        return Math.max(0, deadline - System.currentTimeMillis());
    }

    public long joinLeft() {
        return state == DungeonState.OPEN ? msLeft(joinDeadline) : 0;
    }

    public long countdownLeft() {
        return state == DungeonState.COUNTDOWN ? msLeft(countdownDeadline) : 0;
    }

    public long runLeft() {
        return state.inRun() ? msLeft(runDeadline) : 0;
    }

    public long celebrationLeft() {
        return state == DungeonState.CELEBRATION ? msLeft(celebrationDeadline) : 0;
    }

    private void setState(DungeonState next) {
        state = next;
        cacheDirty = true;
    }

    // ---------------------------------------------------------------- funding

    /** Adds money towards the goal. Returns the message key of what went wrong, or null. */
    public String contribute(Player player, double amount) {
        if (state != DungeonState.FUNDING) return "in-progress";
        if (economy == null) return "no-economy";
        double left = settings.goal - store.current();
        double pay = Math.min(amount, left);
        if (pay <= 0) return "in-progress";
        if (!economy.has(player, pay)) return "not-enough";
        if (!economy.withdraw(player, pay)) return "not-enough";
        store.addFunds(player.getUniqueId(), pay);
        messages.send(player, "contributed", "amount", economy.format(pay));
        if (store.current() >= settings.goal) openForEntry();
        return null;
    }

    /** For admins, and the forced start: as if the goal had been reached. */
    public boolean forceStart(boolean skipWaiting) {
        long now = System.currentTimeMillis();
        if (state == DungeonState.FUNDING) {
            store.setCurrent(Math.max(store.current(), settings.goal));
            openForEntry();
            if (skipWaiting) joinDeadline = now;
            return true;
        }
        if (skipWaiting && state == DungeonState.OPEN) {
            joinDeadline = now;
            return true;
        }
        if (skipWaiting && state == DungeonState.LOCKED) {
            lockDeadline = now;
            return true;
        }
        return false;
    }

    private void openForEntry() {
        setState(DungeonState.OPEN);
        joinDeadline = System.currentTimeMillis() + settings.joinWindowMs;
        Bukkit.broadcast(messages.prefixed("opened", "minutes", Text.number(settings.joinWindowMs / 60000.0)));
    }

    // ---------------------------------------------------------------- joining and leaving

    /** Puts a player into the dungeon. Returns the message key of what went wrong, or null. */
    public String enter(Player player) {
        if (state != DungeonState.OPEN) return "not-open";
        if (isInside(player.getUniqueId())) return "already-in";
        Points.Spot anchor = points.first("anchor");
        Location at = anchor == null ? null : anchor.at();
        if (at == null) {
            if (anchor == null) return "no-anchor";
            messages.send(player, "no-anchor-world", "world", anchor.world());
            return null;
        }

        store.remember(player.getUniqueId(), player.getLocation(), player.getGameMode());
        for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        player.setGameMode(GameMode.SURVIVAL);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.closeInventory();

        Run run = new Run(player.getUniqueId(), player.getName());
        runs.put(player.getUniqueId(), run);
        cacheDirty = true;
        player.teleportAsync(at);
        dialogue.fireTo(Trigger.ON_ENTER_DUNGEON, player, Map.of("player", player.getName()));
        return null;
    }

    /** Sends a player back to exactly where they came from. */
    public void sendBack(Player player) {
        Store.Return back = store.returnOf(player.getUniqueId());
        Location dest = back == null ? null : back.location();
        if (dest == null) dest = Bukkit.getWorlds().get(0).getSpawnLocation();
        Location target = dest;
        GameMode mode = back == null ? GameMode.SURVIVAL : back.mode();
        for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        player.closeInventory();
        // the chunk is loaded before the player arrives, so they cannot fall through unloaded ground
        target.getWorld().getChunkAtAsync(target).thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () ->
                player.teleportAsync(target).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                    player.setGameMode(mode);
                    store.forget(player.getUniqueId());
                }))));
    }

    /** /dungeon leave. */
    public void leave(Player player) {
        Run run = runs.get(player.getUniqueId());
        if (run != null) {
            run.left = true;
            cacheDirty = true;
            artifacts.consume(player, true);
            checkEveryoneDown();
        }
        sendBack(player);
    }

    /** A player died in the run. Keep-inventory is the listener's job; this marks them out. */
    public void died(Player player) {
        Run run = runs.get(player.getUniqueId());
        if (run == null || !run.inside()) return;
        run.eliminated = true;
        cacheDirty = true;
        dialogue.fireExcept(Trigger.ON_PLAYER_DEATH, player, Map.of("player", player.getName()));
        checkEveryoneDown();
    }

    private void checkEveryoneDown() {
        if (!state.inRun() || state == DungeonState.VICTORY || state == DungeonState.CELEBRATION) return;
        if (settings.failWhenEveryoneDead && insideCount() == 0) fail("everyone");
    }

    /** A dead player has respawned where they came from: give them their game mode back and forget the record. */
    public void afterRespawn(Player player) {
        Store.Return back = store.returnOf(player.getUniqueId());
        if (back != null) player.setGameMode(back.mode());
        store.forget(player.getUniqueId());
    }

    /** Where a dead player wakes up: where they came from. */
    public Location respawnFor(Player player) {
        Store.Return back = store.returnOf(player.getUniqueId());
        return back == null ? null : back.location();
    }

    // ---------------------------------------------------------------- the shared tick

    private void tick() {
        long started = Slow.start();
        tickInner();
        Slow.check(plugin, "the dungeon tick (" + state + ")", started);
    }

    private void tickInner() {
        long now = System.currentTimeMillis();
        bosses.tick(now);

        if (now < nextSecond) return;
        nextSecond = now + 1000;

        if (waiting()) waitingBar(now);
        switch (state) {
            case OPEN -> tickOpen(now);
            case LOCKED -> {
                if (now >= lockDeadline) {
                    setState(DungeonState.COUNTDOWN);
                    countdownDeadline = now + settings.countdownMs;
                    shownCountdown = -1;
                }
            }
            case COUNTDOWN -> tickCountdown(now);
            case ROOM1_TRAVEL, ROOM2_TRAVEL, ROOM3_TRAVEL -> tickTravel(now);
            case ROOM1_COMBAT, ROOM2_COMBAT, ROOM3_COMBAT -> tickCombat(now);
            case ROOM1_LOOT -> tickLoot();
            case CELEBRATION -> tickCelebration(now);
            default -> { }
        }
        if (state.inRun() && state != DungeonState.VICTORY && state != DungeonState.CELEBRATION && now >= runDeadline) {
            fail("time");
            return;
        }
        if (state.active()) sweepIntruders();
    }

    /** True while players are waiting in the spawn hall for the run to begin. */
    public boolean waiting() {
        return state == DungeonState.OPEN || state == DungeonState.LOCKED || state == DungeonState.COUNTDOWN;
    }

    /** The time until the first gate opens, on the action bar of everyone waiting. */
    private void waitingBar(long now) {
        long left = switch (state) {
            case OPEN -> msLeft(joinDeadline) + settings.lockGapMs + settings.countdownMs;
            case LOCKED -> msLeft(lockDeadline) + settings.countdownMs;
            default -> msLeft(countdownDeadline);
        };
        Component bar = messages.text("waiting", "time", clock(left), "players", String.valueOf(insideCount()));
        for (Player p : players()) p.sendActionBar(bar);
    }

    private void tickOpen(long now) {
        if (now < joinDeadline) return;
        if (insideCount() == 0) {
            // nobody came: money back if configured, and funding starts over
            if (settings.refundWhenNobodyJoins && economy != null) {
                store.paid().forEach((id, amount) -> economy.deposit(Bukkit.getOfflinePlayer(id), amount));
            }
            store.clearFunding();
            setState(DungeonState.FUNDING);
            Bukkit.broadcast(messages.prefixed("nobody-came"));
            return;
        }
        setState(DungeonState.LOCKED);
        lockDeadline = now + settings.lockGapMs;
        for (Player p : players()) messages.send(p, "sealing");
    }

    private void tickCountdown(long now) {
        int left = (int) Math.ceil(msLeft(countdownDeadline) / 1000.0);
        if (left != shownCountdown && left > 0) {
            shownCountdown = left;
            for (Player p : players()) {
                p.showTitle(Title.title(Text.component("<#FF5555><bold>" + left), Component.empty(),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ZERO)));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
            }
        }
        if (now >= countdownDeadline) startRun(now);
    }

    private void startRun(long now) {
        setState(DungeonState.ROOM1_TRAVEL);
        runDeadline = now + settings.runLimitMs;
        minibossDead = false;
        store.runActive(true);
        artifacts.newRun(settings.artifactsRoom1);
        dialogue.resetCooldowns();

        announce(settings.startMessages, "start", "%participant_count%", String.valueOf(insideCount()));

        RoomDef room1 = rooms.room(1);
        if (room1 != null && room1.door() != null) doors.open(room1.door());
        dialogue.fire(Trigger.ON_ROOM1_UNLOCK, Map.of());
        fx.sound(Sound.BLOCK_END_PORTAL_SPAWN, 0.6f, 0.7f);
    }

    // ---------------------------------------------------------------- travelling into a room

    private static int travelLevel(DungeonState s) {
        return switch (s) {
            case ROOM1_TRAVEL -> 1;
            case ROOM2_TRAVEL -> 2;
            default -> 3;
        };
    }

    private Region coreOf(RoomDef room) {
        return room == null || room.core() == null ? null : regions.get(room.core());
    }

    private void tickTravel(long now) {
        int level = travelLevel(state);
        RoomDef room = rooms.room(level);
        Region core = coreOf(room);
        List<Player> outside = new ArrayList<>();
        int insideRoom = 0;
        for (Player p : players()) {
            Run run = runs.get(p.getUniqueId());
            // inside means in the heart of the room, not in the corridor that leads to it
            boolean in = core != null
                    ? p.getWorld().getName().equals(core.world()) && core.contains(p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ())
                    : run.progress.level() >= level;
            if (in) insideRoom++;
            else outside.add(p);
        }
        if (outside.isEmpty()) {
            if (insideRoom > 0) beginRoom(level);
            return;
        }
        Region target = core != null ? core : regions.firstOfType(RegionType.valueOf("ROOM" + level));
        for (Player p : outside) {
            if (target != null) p.sendActionBar(compass(p, target));
            Run run = runs.get(p.getUniqueId());
            // once somebody is in, the stragglers are told to hurry
            if (insideRoom > 0 && now >= run.nextWarn) {
                run.nextWarn = now + settings.warnIntervalMs;
                p.sendActionBar(messages.text("return-warning"));
                p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 0.6f, 0.8f);
            }
        }
    }

    private Component compass(Player p, Region target) {
        double dx = target.centerX() - p.getLocation().getX();
        double dz = target.centerZ() - p.getLocation().getZ();
        double degrees = (Math.toDegrees(Math.atan2(dx, -dz)) + 360) % 360;
        int index = (int) Math.round(degrees / 45) % 8;
        return messages.text("compass", "arrow", ARROWS[index], "distance", String.valueOf((int) Math.hypot(dx, dz)), "direction", COMPASS[index]);
    }

    private void beginRoom(int level) {
        RoomDef room = rooms.room(level);
        if (room == null) {
            plugin.getLogger().warning("rooms.yml has no room with level " + level);
            return;
        }
        // the way back closes so nothing wanders out and nobody is left behind
        if (room.seal() != null) doors.close(room.seal());
        for (Player p : players()) p.sendActionBar(messages.text("sealing-room"));
        waves.players(insideCount());

        switch (level) {
            case 1 -> {
                setState(DungeonState.ROOM1_COMBAT);
                startWaves(room, room.waves(), room.spawnGroup(), "room1", this::room1Cleared);
            }
            case 2 -> {
                setState(DungeonState.ROOM2_COMBAT);
                startWaves(room, room.waves(), room.spawnGroup(), "room2", this::spawnMiniboss);
            }
            default -> {
                setState(DungeonState.ROOM3_COMBAT);
                List<Rooms.WaveDef> guards = List.of(new Rooms.WaveDef("Guards", room.guards()));
                startWaves(room, guards, room.guardGroup(), "guards", () -> bosses.storm(bossPoint(room), 3, () -> spawnFinal(room)));
            }
        }
    }

    private void startWaves(RoomDef room, List<Rooms.WaveDef> list, String group, String tier, Runnable cleared) {
        waves.start(list, group, settings.maxActiveMobs, settings.scaleMobs ? settings.extraMobsPerPlayer : 0, 3000,
                (dead, killer) -> mobKilled(killer, tier),
                number -> waveStarted(number, list.get(number - 1).name()), cleared);
    }

    private void waveStarted(int number, String name) {
        dialogue.fire(Trigger.ON_WAVE_START, Map.of("wave", String.valueOf(number)));
        titles.type(players(), messages.raw("wave-title", "wave", String.valueOf(number)), "#FF5555", messages.raw("wave-subtitle"));
        for (Player p : players()) p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 0.5f, 1.2f);
    }

    /** The way in and out of a room lifts again once its fight is over. */
    private void unseal(int level) {
        RoomDef room = rooms.room(level);
        if (room != null && room.reopen() && room.seal() != null) doors.open(room.seal());
    }

    /** While the artifacts are hidden: a faint glint over each chest for players who are close, and the count so far. */
    private void tickLoot() {
        for (Location at : artifacts.hiddenAt()) {
            for (Player p : players()) {
                if (p.getWorld() == at.getWorld() && p.getLocation().distanceSquared(at) <= 400) {
                    p.spawnParticle(org.bukkit.Particle.END_ROD, at.clone().add(0.5, 1.2, 0.5), 3, 0.15, 0.2, 0.15, 0.01);
                }
            }
        }
        Component bar = messages.text("loot-bar", "found", String.valueOf(artifacts.room1Found()), "total", String.valueOf(artifacts.room1Quota()));
        for (Player p : players()) p.sendActionBar(bar);
    }

    private void mobKilled(Player killer, String tier) {
        if (killer != null) {
            rewards.kill(killer, tier);
            dialogue.fireTo(Trigger.ON_MOB_KILL, killer, Map.of("player", killer.getName()));
        }
    }

    private void tickCombat(long now) {
        mobs.tick(now);
        waves.players(Math.max(1, players().size()));
        waves.tick(now, settings.wavePauseMs);
    }

    // ---------------------------------------------------------------- room 1: the search

    private void room1Cleared() {
        setState(DungeonState.ROOM1_LOOT);
        int hidden = artifacts.hideRoom1(points);
        unseal(1);
        titles.type(players(), messages.raw("loot-title"), "#B3A2FF", messages.raw("loot-subtitle", "count", String.valueOf(artifacts.room1Quota())));
        if (hidden == 0 || artifacts.room1Quota() == 0) unlockRoom(2);
    }

    private void artifactFound(Artifacts.Found found) {
        for (Player p : players()) {
            p.sendActionBar(messages.text("artifact-found", "player", found.finder().getName(), "artifact", found.name()));
            p.sendMessage(messages.prefixed("artifact-found", "player", found.finder().getName(), "artifact", found.name()));
        }
        dialogue.fire(Trigger.ON_ARTIFACT_FOUND, Map.of("player", found.finder().getName(), "found", String.valueOf(found.order()),
                "total", String.valueOf(artifacts.total())));
        fx.sound(Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 0.8f);
        if (state == DungeonState.ROOM1_LOOT && artifacts.room1Found() >= artifacts.room1Quota()) unlockRoom(2);
        checkThroneUnlock();
    }

    private void unlockRoom(int level) {
        RoomDef room = rooms.room(level);
        if (room != null && room.door() != null) doors.open(room.door());
        setState(level == 2 ? DungeonState.ROOM2_TRAVEL : DungeonState.ROOM3_TRAVEL);
        dialogue.fire(level == 2 ? Trigger.ON_ROOM2_UNLOCK : Trigger.ON_ROOM3_UNLOCK, Map.of());
        fx.sound(Sound.BLOCK_END_PORTAL_SPAWN, 0.6f, 0.7f);
    }

    // ---------------------------------------------------------------- room 2: the mini-boss

    private void spawnMiniboss() {
        RoomDef room = rooms.room(2);
        Points.Spot spot = points.first(room.minibossPoint() == null ? "altar" : room.minibossPoint());
        Location at = spot == null ? null : spot.at();
        if (room.miniboss() == null || at == null) {
            minibossDead = true;
            artifacts.dropRoom2(playerCenter(), new ArrayList<>(players()), settings.autoDistribute);
            checkThroneUnlock();
            return;
        }
        dialogue.fire(Trigger.ON_MINIBOSS_SPAWN, Map.of());
        bosses.spawn(room.miniboss(), at, new Bosses.Events() {
            @Override
            public void lowHealth(Bosses.BossFight fight) {
                // the mini-boss has no low-health beat of its own
            }

            @Override
            public void died(Bosses.BossFight fight, Player lastHit, Location where) {
                minibossDead = true;
                mobs.killAll();   // its summoned vexes go with it
                unseal(2);
                mobKilled(lastHit, "miniboss");
                artifacts.dropRoom2(where, new ArrayList<>(players()), settings.autoDistribute);
                checkThroneUnlock();
            }
        });
    }

    private Location playerCenter() {
        for (Player p : players()) return p.getLocation();
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    /** One check, on the total, unlocks the throne room. Not "room 1 done and room 2 done". */
    private void checkThroneUnlock() {
        if (state == DungeonState.ROOM2_COMBAT && minibossDead && artifacts.complete()) unlockRoom(3);
    }

    // ---------------------------------------------------------------- room 3: the throne

    private Location bossPoint(RoomDef room) {
        Points.Spot spot = points.first(room.bossPoint() == null ? "boss" : room.bossPoint());
        Location at = spot == null ? null : spot.at();
        return at != null ? at : playerCenter();
    }

    private void spawnFinal(RoomDef room) {
        if (state != DungeonState.ROOM3_COMBAT) return;
        Bosses.BossFight fight = bosses.spawn(room.boss(), bossPoint(room), new Bosses.Events() {
            @Override
            public void lowHealth(Bosses.BossFight fight) {
                dialogue.fire(Trigger.ON_BOSS_LOW_HP, Map.of());
            }

            @Override
            public void died(Bosses.BossFight fight, Player lastHit, Location where) {
                victory(lastHit, fight.def.mob().name(), where);
            }
        });
        if (fight == null) {
            plugin.getLogger().warning("The final boss '" + room.boss() + "' is not in bosses.yml");
            return;
        }
        bossName = fight.def.mob().name();
        dialogue.fire(Trigger.ON_BOSS_SPAWN, Map.of("boss", bossName));
    }

    // ---------------------------------------------------------------- the end

    private void victory(Player last, String boss, Location where) {
        if (state != DungeonState.ROOM3_COMBAT) return;
        setState(DungeonState.VICTORY);
        lastHit = last == null ? "the party" : last.getName();
        store.result("VICTORY", Text.plain(boss));
        unseal(3);
        waves.stop();
        mobs.killAll();

        ItemStack special = artifacts.makeSpecial();
        if (special != null && where != null) where.getWorld().dropItemNaturally(where, special).setGlowing(true);
        for (Player p : players()) {
            rewards.victory(p);
            artifacts.consume(p, settings.consumeArtifacts);
        }
        announce(settings.victoryMessages, "victory", "%last_hit_player%", lastHit, "%boss_name%", Text.plain(boss));
        dialogue.fire(Trigger.ON_VICTORY, Map.of("player", lastHit));
        fx.sound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        tasks.later(60, () -> {
            setState(DungeonState.CELEBRATION);
            celebrationDeadline = System.currentTimeMillis() + settings.celebrationMs;
            nextFirework = 0;
        });
    }

    private void tickCelebration(long now) {
        if (now >= celebrationDeadline) {
            beginReset();
            return;
        }
        // a few every few seconds, not a barrage, and they clean themselves up
        if (now >= nextFirework) {
            nextFirework = now + 3000;
            Location centre = bossPoint(rooms.room(3) == null ? rooms.room(1) : rooms.room(3));
            for (int i = 0; i < 3; i++) {
                Location at = centre.clone().add((Math.random() - 0.5) * 16, 1, (Math.random() - 0.5) * 16);
                Firework fw = centre.getWorld().spawn(at, Firework.class);
                FireworkMeta meta = fw.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.RED, Color.YELLOW, Color.WHITE)
                        .withFade(Color.ORANGE).flicker(true).build());
                meta.setPower(1);
                fw.setFireworkMeta(meta);
            }
        }
    }

    /** The run is lost: time ran out, everybody died, or an admin ended it. */
    public void fail(String reason) {
        if (!state.inRun() || state == DungeonState.VICTORY || state == DungeonState.CELEBRATION) return;
        String boss = bossName.isEmpty() ? "the guardian" : bossName;
        for (Bosses.BossFight fight : List.copyOf(bosses.fights())) {
            if (fight.def.dramaticDeath()) boss = Text.plain(fight.def.mob().name());
            bosses.escape(fight);
        }
        setState(DungeonState.FAILED);
        store.result("FAILED", Text.plain(boss));
        waves.stop();
        announce(settings.failureMessages, "failure", "%boss_name%", Text.plain(boss));
        dialogue.fire(Trigger.ON_FAILURE, Map.of("reason", reason));
        tasks.later(80, this::beginReset);
    }

    /** Anything an admin does to end a run early. */
    public void forceEnd() {
        if (state.inRun()) fail("forced");
        else if (state != DungeonState.FUNDING && state != DungeonState.RESETTING) beginReset();
    }

    /** A message to the whole server. The config can list several and one is picked; else the messages.yml one is used. */
    private void announce(List<String> variants, String key, String... pairs) {
        String text = variants.isEmpty() ? messages.raw(key) : variants.get(ThreadLocalRandom.current().nextInt(variants.size()));
        for (int i = 0; i + 1 < pairs.length; i += 2) text = text.replace(pairs[i], pairs[i + 1]);
        Bukkit.broadcast(Text.component(text));
    }

    // ---------------------------------------------------------------- resetting

    /** Everybody out, everything cleaned, the world restored, and then funding opens again. */
    public void beginReset() {
        if (state == DungeonState.RESETTING) return;
        setState(DungeonState.RESETTING);
        long t = Slow.start();
        tasks.cancelAll();
        waves.stop();
        mobs.killAll();
        bosses.stopAll();
        Slow.check(plugin, "reset: stopping mobs and tasks", t);
        t = Slow.start();
        for (Run run : runs.values()) {
            Player p = Bukkit.getPlayer(run.id);
            if (p != null) {
                artifacts.consume(p, true);
                sendBack(p);
            }
        }
        cacheDirty = true;
        Slow.check(plugin, "reset: sending players home", t);
        doors.markClosed();

        String worldName = settings.world;
        World world = Bukkit.getWorld(worldName);
        File file = new File(plugin.getDataFolder(), "schematics/dungeon_clean.schem");
        if (world == null || !file.exists()) {
            plugin.getLogger().warning("No world '" + worldName + "' or no schematics/dungeon_clean.schem, so the world is not restored.");
            doors.closeAllNow();
            finishReset();
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Snapshot snapshot;
            try {
                snapshot = SchemFile.read(file.toPath());
            } catch (Exception e) {
                plugin.getLogger().warning("Could not read schematics/dungeon_clean.schem: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    doors.closeAllNow();
                    finishReset();
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> reset.restore(snapshot, world, true, result -> {
                plugin.getLogger().info("Dungeon restored: " + result.changed() + " blocks changed in " + result.millis() + " ms over " + result.chunks() + " chunks.");
                finishReset();
            }, null));
        });
    }

    private void finishReset() {
        runs.clear();
        cacheDirty = true;
        artifacts.clear();
        store.clearFunding();
        store.runActive(false);
        minibossDead = false;
        bossName = "";
        setState(DungeonState.FUNDING);
        Bukkit.broadcast(messages.prefixed("ready"));
    }

    /** Players who are not in the run must not wander into it while it is going on. */
    private void sweepIntruders() {
        World world = Bukkit.getWorld(settings.world);
        if (world == null) return;
        for (Player p : world.getPlayers()) {
            if (runs.containsKey(p.getUniqueId()) || p.hasPermission("dungeon.bypass.entry")) continue;
            Location l = p.getLocation();
            if (regions.inType(world.getName(), l.getX(), l.getY(), l.getZ(), RegionType.DUNGEON)) {
                p.teleportAsync(Bukkit.getWorlds().get(0).getSpawnLocation());
                messages.send(p, "keep-out");
            }
        }
    }

    // ---------------------------------------------------------------- movement

    /** True if a step from one block to another must be stopped. Also records the room a player walks into. */
    public boolean blocksMove(Player player, Location from, Location to) {
        Run run = runs.get(player.getUniqueId());
        if (run == null || !run.inside() || to.getWorld() == null) return false;
        String world = to.getWorld().getName();
        Region step = regions.findStep(world, to.getX(), to.getY(), to.getZ());
        int unlocked = state.unlockedLevel();
        if (step != null) {
            int level = step.type().level();
            if (level > unlocked) return true;
            if (level > run.progress.level()) {
                run.progress = switch (level) {
                    case 1 -> RoomProgress.ROOM1;
                    case 2 -> RoomProgress.ROOM2;
                    default -> RoomProgress.ROOM3;
                };
            }
            return false;
        }
        // until the doors open, spawn is a cell
        if (unlocked == 0 && regions.inType(world, from.getX(), from.getY(), from.getZ(), RegionType.SPAWN)) {
            return regions.firstOfType(RegionType.SPAWN) != null;
        }
        return false;
    }

    // ---------------------------------------------------------------- placeholders

    public String placeholder(Player player, String id) {
        return switch (id) {
            case "state" -> state.pretty();
            case "goal_current" -> Text.number(store.current());
            case "goal_total" -> Text.number(settings.goal);
            case "goal_percent" -> String.valueOf((int) Math.min(100, store.current() * 100 / settings.goal));
            case "join_time_left" -> clock(joinLeft());
            case "countdown" -> String.valueOf((int) Math.ceil(countdownLeft() / 1000.0));
            case "run_time_left" -> clock(runLeft());
            case "celebration_time_left" -> clock(celebrationLeft());
            case "players_inside" -> String.valueOf(insideCount());
            case "artifacts_found" -> String.valueOf(artifacts.found());
            case "artifacts_total" -> String.valueOf(artifacts.total());
            case "current_room" -> {
                Run run = player == null ? null : runs.get(player.getUniqueId());
                yield run == null ? "None" : run.progress.name();
            }
            case "boss_name" -> {
                for (Bosses.BossFight f : bosses.fights()) yield Text.plain(f.def.mob().name());
                yield "None";
            }
            case "boss_hp_percent" -> {
                for (Bosses.BossFight f : bosses.fights()) {
                    var max = f.entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                    if (max != null) yield String.valueOf((int) (f.entity.getHealth() * 100 / max.getValue()));
                }
                yield "0";
            }
            case "last_result" -> store.lastResult() + (store.lastBoss().isEmpty() ? "" : " " + store.lastBoss());
            case "coins" -> player == null ? "0" : String.valueOf(Rewards.coins(player));
            default -> null;
        };
    }

    public static String clock(long ms) {
        long s = (ms + 999) / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }
}
