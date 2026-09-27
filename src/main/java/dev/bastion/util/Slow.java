package dev.bastion.util;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A guard for the main thread: anything that takes longer than the limit is logged with its name, so a step that
 * stalls a tick shows up in the console instead of as unexplained lag. Warnings are throttled: a step that is
 * consistently slow (e.g. the dungeon tick under server load) would otherwise flood the console several times a
 * second instead of once.
 */
public final class Slow {

    private static final long LIMIT_NANOS = 25_000_000L;
    private static final long WARN_WINDOW_MS = 10_000L;

    private static final AtomicLong nextWarnAt = new AtomicLong();
    private static final AtomicInteger suppressed = new AtomicInteger();

    private Slow() {
    }

    public static long start() {
        return System.nanoTime();
    }

    public static void check(Plugin plugin, String what, long startedNanos) {
        long took = System.nanoTime() - startedNanos;
        if (took <= LIMIT_NANOS) return;
        long now = System.currentTimeMillis();
        long next = nextWarnAt.get();
        if (now < next) {
            suppressed.incrementAndGet();
            return;
        }
        if (!nextWarnAt.compareAndSet(next, now + WARN_WINDOW_MS)) return;
        int skipped = suppressed.getAndSet(0);
        String suffix = skipped > 0 ? " (" + skipped + " more warnings suppressed)" : "";
        plugin.getLogger().warning("Slow step: " + what + " took " + took / 1_000_000 + " ms" + suffix);
    }
}
