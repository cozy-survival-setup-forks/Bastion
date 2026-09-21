package dev.bastion.dungeon;

import java.util.UUID;

/** One player's part in the current run. */
public final class Run {
    public final UUID id;
    public final String name;
    public RoomProgress progress = RoomProgress.SPAWN;
    public boolean eliminated;
    public boolean left;
    public long lastBlockKey = Long.MIN_VALUE;
    public long nextWarn;
    public long nextChatterAt;

    public Run(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean inside() {
        return !eliminated && !left;
    }
}
