package dev.bastion.dungeon;

import java.util.Locale;

/** The one source of truth: timers, doors, menus and command blocking all follow this. */
public enum DungeonState {
    FUNDING, OPEN, LOCKED, COUNTDOWN,
    ROOM1_TRAVEL, ROOM1_COMBAT, ROOM1_LOOT,
    ROOM2_TRAVEL, ROOM2_COMBAT,
    ROOM3_TRAVEL, ROOM3_COMBAT,
    VICTORY, CELEBRATION, FAILED, RESETTING;

    /** How far into the dungeon players may go: 0 spawn only, 1 to 3 the rooms. */
    public int unlockedLevel() {
        return switch (this) {
            case ROOM1_TRAVEL, ROOM1_COMBAT, ROOM1_LOOT -> 1;
            case ROOM2_TRAVEL, ROOM2_COMBAT -> 2;
            case ROOM3_TRAVEL, ROOM3_COMBAT, VICTORY, CELEBRATION -> 3;
            default -> 0;
        };
    }

    /** True from the first room's door opening until the run is over. */
    public boolean inRun() {
        return ordinal() >= ROOM1_TRAVEL.ordinal() && ordinal() <= CELEBRATION.ordinal();
    }

    /** True from the goal being reached until the dungeon is ready for funding again. */
    public boolean active() {
        return this != FUNDING;
    }

    public String pretty() {
        String s = name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
