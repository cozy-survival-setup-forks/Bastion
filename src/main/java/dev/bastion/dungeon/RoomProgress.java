package dev.bastion.dungeon;

public enum RoomProgress {
    SPAWN, ROOM1, ROOM1_CLEARED, ROOM2, ROOM2_CLEARED, ROOM3, VICTORY;

    /** The step of the run this is, 0 for the spawn hall to 3 for the throne room. */
    public int level() {
        return switch (this) {
            case SPAWN -> 0;
            case ROOM1, ROOM1_CLEARED -> 1;
            case ROOM2, ROOM2_CLEARED -> 2;
            default -> 3;
        };
    }
}
