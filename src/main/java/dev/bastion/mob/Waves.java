package dev.bastion.mob;

import dev.bastion.dungeon.Rooms.WaveDef;
import dev.bastion.world.Points;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/**
 * Runs the waves of one room. A wave only ends when every mob of it is dead. Mobs come out in batches so no more than
 * the cap are alive at once, however big the party is, which keeps pathfinding cost bounded.
 */
public final class Waves {

    private final Mobs mobs;
    private final Points points;

    private List<WaveDef> waves = List.of();
    private String spawnGroup;
    private int wave;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Set<UUID> alive = new HashSet<>();
    private int cap = 8;
    private long nextWaveAt;
    private boolean running, resting;
    private Runnable cleared;
    private IntConsumer waveStarted;
    private BiConsumer<LivingEntity, Player> onKill;
    private double extraPerPlayer;
    private int players = 1;

    public Waves(Mobs mobs, Points points) {
        this.mobs = mobs;
        this.points = points;
    }

    /**
     * @param onKill told about every kill, with the killer, for rewards and dialogue
     * @param waveStarted told the number of the wave (from 1) as it begins
     * @param cleared told once, when the last wave is dead
     */
    public void start(List<WaveDef> waves, String spawnGroup, int cap, double extraPerPlayer, long firstDelayMs,
                      BiConsumer<LivingEntity, Player> onKill, IntConsumer waveStarted, Runnable cleared) {
        stop();
        this.waves = waves;
        this.spawnGroup = spawnGroup;
        this.cap = cap;
        this.extraPerPlayer = extraPerPlayer;
        this.onKill = onKill;
        this.waveStarted = waveStarted;
        this.cleared = cleared;
        this.wave = 0;
        this.running = true;
        this.resting = true;
        this.nextWaveAt = System.currentTimeMillis() + firstDelayMs;
        if (waves.isEmpty()) finish();
    }

    public void stop() {
        running = false;
        queue.clear();
        alive.clear();
    }

    public boolean running() {
        return running;
    }

    public int alive() {
        return alive.size();
    }

    public int wave() {
        return wave;
    }

    public int waveCount() {
        return waves.size();
    }

    /** The party size, used to scale the next waves. */
    public void players(int count) {
        this.players = Math.max(1, count);
    }

    /** Called about once a second. */
    public void tick(long now, long pauseMs) {
        if (!running) return;
        if (resting) {
            if (now < nextWaveAt) return;
            resting = false;
            beginWave();
        }
        while (!queue.isEmpty() && alive.size() < cap) spawnOne();
        if (queue.isEmpty() && alive.isEmpty()) {
            if (wave >= waves.size()) {
                finish();
            } else {
                resting = true;
                nextWaveAt = now + pauseMs;
            }
        }
    }

    private void beginWave() {
        WaveDef def = waves.get(wave);
        wave++;
        double factor = 1 + extraPerPlayer * (players - 1);
        for (Map.Entry<String, Integer> e : def.mobs().entrySet()) {
            int count = Math.max(1, (int) Math.ceil(e.getValue() * factor));
            for (int i = 0; i < count; i++) queue.add(e.getKey());
        }
        if (waveStarted != null) waveStarted.accept(wave);
    }

    private void spawnOne() {
        String id = queue.poll();
        Points.Spot spot = points.random(spawnGroup);
        Location at = spot == null ? null : spot.at();
        if (at == null) {
            // nowhere to spawn: skip it rather than hang the wave forever
            return;
        }
        UUID[] self = new UUID[1];
        LivingEntity entity = mobs.spawn(id, at, (dead, killer) -> {
            if (self[0] != null) alive.remove(self[0]);
            if (onKill != null && dead != null) onKill.accept(dead, killer);
        });
        if (entity != null) {
            self[0] = entity.getUniqueId();
            alive.add(self[0]);
        }
    }

    private void finish() {
        running = false;
        if (cleared != null) cleared.run();
    }
}
