package dev.bastion.util;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Particles and sounds go to the players in the run and nobody else: no radius broadcasts, so nothing leaks out of
 * the instance and the cost stays proportional to the party.
 */
public final class Fx {

    private static final java.util.Set<String> BROKEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Supplier<Collection<Player>> audience;

    public Fx(Supplier<Collection<Player>> audience) {
        this.audience = audience;
    }

    public Collection<Player> players() {
        return audience.get();
    }

    public void particle(Particle particle, Location at, int count, double dx, double dy, double dz, double speed) {
        particle(particle, at, count, dx, dy, dz, speed, null);
    }

    public <T> void particle(Particle particle, Location at, int count, double dx, double dy, double dz, double speed, T data) {
        Object extra = data;
        // some particles need a number (the size of an explosion, say) and throw without one
        if (extra == null && particle.getDataType() == Float.class) extra = 1f;
        try {
            for (Player player : audience.get()) {
                if (player.getWorld() == at.getWorld()) player.spawnParticle(particle, at, count, dx, dy, dz, speed, extra);
            }
        } catch (IllegalArgumentException e) {
            // a bad particle must never break a fight: skip it
            if (BROKEN.add(particle.name())) java.util.logging.Logger.getLogger("Bastion").warning("Cannot show particle " + particle + ": " + e.getMessage());
        }
    }

    public void sound(Sound sound, Location at, float volume, float pitch) {
        for (Player player : audience.get()) {
            if (player.getWorld() == at.getWorld()) player.playSound(at, sound, org.bukkit.SoundCategory.HOSTILE, volume, pitch);
        }
    }

    public void sound(Sound sound, float volume, float pitch) {
        for (Player player : audience.get()) player.playSound(player.getLocation(), sound, org.bukkit.SoundCategory.MASTER, volume, pitch);
    }
}
