package dev.bastion.util;

import org.bukkit.plugin.Plugin;

/**
 * A guard for the main thread: anything that takes longer than the limit is logged with its name, so a step that
 * stalls a tick shows up in the console instead of as unexplained lag.
 */
public final class Slow {

    private static final long LIMIT_NANOS = 25_000_000L;

    private Slow() {
    }

    public static long start() {
        return System.nanoTime();
    }

    public static void check(Plugin plugin, String what, long startedNanos) {
        long took = System.nanoTime() - startedNanos;
        if (took > LIMIT_NANOS) plugin.getLogger().warning("Slow step: " + what + " took " + took / 1_000_000 + " ms");
    }
}
