package dev.bastion.region;

import java.util.Locale;

public enum RegionType {
    /** The hall players arrive in. Level 0. */
    SPAWN(0),
    ROOM1(1),
    ROOM2(2),
    ROOM3(3),
    /** The whole dungeon, for reset and keeping outsiders out. */
    DUNGEON(-1),
    DOOR_TRIGGER(-1),
    CHEST_ZONE(-1),
    OTHER(-1);

    private final int level;

    RegionType(int level) {
        this.level = level;
    }

    /** How far into the dungeon this is, or -1 if it is not a step of the run. */
    public int level() {
        return level;
    }

    public static RegionType parse(String text) {
        try {
            return valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
