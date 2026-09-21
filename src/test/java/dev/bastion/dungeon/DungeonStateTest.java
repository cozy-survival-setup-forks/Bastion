package dev.bastion.dungeon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonStateTest {

    @Test
    void doorsFollowTheStateAndOnlyOpenForward() {
        assertEquals(0, DungeonState.FUNDING.unlockedLevel());
        assertEquals(0, DungeonState.OPEN.unlockedLevel());
        assertEquals(0, DungeonState.COUNTDOWN.unlockedLevel());
        assertEquals(1, DungeonState.ROOM1_TRAVEL.unlockedLevel());
        assertEquals(1, DungeonState.ROOM1_LOOT.unlockedLevel());
        assertEquals(2, DungeonState.ROOM2_TRAVEL.unlockedLevel());
        assertEquals(2, DungeonState.ROOM2_COMBAT.unlockedLevel());
        assertEquals(3, DungeonState.ROOM3_COMBAT.unlockedLevel());
        assertEquals(0, DungeonState.RESETTING.unlockedLevel());
    }

    @Test
    void levelNeverGoesBackWithinARun() {
        int last = 0;
        for (DungeonState s : DungeonState.values()) {
            if (!s.inRun()) continue;
            assertTrue(s.unlockedLevel() >= last, s + " unlocks less than the state before it");
            last = s.unlockedLevel();
        }
    }

    @Test
    void theRunIsFromTheFirstDoorToTheCelebration() {
        assertFalse(DungeonState.COUNTDOWN.inRun());
        assertTrue(DungeonState.ROOM1_TRAVEL.inRun());
        assertTrue(DungeonState.VICTORY.inRun());
        assertTrue(DungeonState.CELEBRATION.inRun());
        assertFalse(DungeonState.FAILED.inRun());
        assertFalse(DungeonState.RESETTING.inRun());
    }

    @Test
    void onlyFundingIsIdle() {
        for (DungeonState s : DungeonState.values()) assertEquals(s != DungeonState.FUNDING, s.active(), s.name());
    }

    @Test
    void progressLevelsMatchTheRooms() {
        assertEquals(0, RoomProgress.SPAWN.level());
        assertEquals(1, RoomProgress.ROOM1.level());
        assertEquals(1, RoomProgress.ROOM1_CLEARED.level());
        assertEquals(2, RoomProgress.ROOM2.level());
        assertEquals(3, RoomProgress.ROOM3.level());
        assertEquals(3, RoomProgress.VICTORY.level());
    }

    @Test
    void clockShowsMinutesAndSeconds() {
        assertEquals("05:00", Dungeon.clock(300_000));
        assertEquals("00:01", Dungeon.clock(1));
        assertEquals("60:00", Dungeon.clock(3_600_000));
        assertEquals("00:00", Dungeon.clock(0));
    }
}
