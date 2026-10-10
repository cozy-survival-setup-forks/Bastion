package dev.bastion.dungeon;

import dev.bastion.safe.Db;
import dev.bastion.util.SetupFile;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {

    private static final Logger LOG = Logger.getAnonymousLogger();
    private static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ALEX = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path dir;

    private Store open() throws Exception {
        Store store = new Store(dir, LOG);
        store.open(3);
        return store;
    }

    private String url() {
        return "jdbc:sqlite:" + dir.resolve("bastion.db").toAbsolutePath();
    }

    private static final String OLD_FILE = """
            funding:
              current: 1250.5
              paid:
                11111111-1111-1111-1111-111111111111: 1000.0
                22222222-2222-2222-2222-222222222222: 250.5
            run-active: true
            last-result: VICTORY
            last-boss: The Sovereign
            returns:
              11111111-1111-1111-1111-111111111111:
                world: world
                x: 1.5
                y: 64.0
                z: -3.5
                yaw: 90.0
                pitch: 10.0
                mode: SURVIVAL
            """;

    @Test
    void fundingSurvivesARestart() throws Exception {
        Store store = open();
        assertTrue(store.addFunds(STEVE, 100.25, null));
        assertTrue(store.addFunds(STEVE, 50, null));
        assertTrue(store.addFunds(ALEX, 10, null));
        store.result("FAILED", "Boss");
        store.runActive(true);
        store.close();

        Store again = open();
        assertEquals(160.25, again.current(), 1e-9);
        assertEquals(150.25, again.paid().get(STEVE), 1e-9);
        assertEquals(10, again.paid().get(ALEX), 1e-9);
        assertTrue(again.runActive());
        assertEquals("FAILED", again.lastResult());
        assertEquals("Boss", again.lastBoss());
        again.close();
    }

    @Test
    void anOldDataFileIsBroughtInOnceAndSetAside() throws Exception {
        Files.writeString(dir.resolve("data.yml"), OLD_FILE);
        Store store = open();
        assertEquals(1250.5, store.current(), 1e-9);
        assertEquals(1000.0, store.paid().get(STEVE), 1e-9);
        assertTrue(store.runActive());
        assertEquals("VICTORY", store.lastResult());
        assertEquals("The Sovereign", store.lastBoss());
        assertTrue(store.hasReturns());
        assertEquals(64.0, store.returnOf(STEVE).y(), 1e-9);
        assertEquals(org.bukkit.GameMode.SURVIVAL, store.returnOf(STEVE).mode());
        assertFalse(Files.exists(dir.resolve("data.yml")));
        assertTrue(Files.exists(dir.resolve("data.yml.migrated")));
        store.close();

        Files.writeString(dir.resolve("data.yml"), "funding:\n  current: 999999\n");
        Store again = open();
        assertEquals(1250.5, again.current(), 1e-9);
        again.close();
    }

    @Test
    void anUnreadableDataFileStopsTheStartAndIsLeftAlone() throws Exception {
        String broken = "funding: [unclosed\n";
        Files.writeString(dir.resolve("data.yml"), broken);
        Store store = new Store(dir, LOG);
        assertThrows(InvalidConfigurationException.class, () -> store.open(3));
        assertEquals(broken, Files.readString(dir.resolve("data.yml")));

        // money that is owed back is never skipped
        String badId = "funding:\n  current: 5\n  paid:\n    not-a-uuid: 5.0\n";
        Files.writeString(dir.resolve("data.yml"), badId);
        Store second = new Store(dir, LOG);
        assertThrows(InvalidConfigurationException.class, () -> second.open(3));
        assertEquals(badId, Files.readString(dir.resolve("data.yml")));
    }

    @Test
    void anImportThatStopsHalfWayLeavesTheFileAndNothingElse() throws Exception {
        Files.writeString(dir.resolve("data.yml"), OLD_FILE);
        try (Connection c = DriverManager.getConnection(url()); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("CREATE TABLE paid (player TEXT PRIMARY KEY, amount REAL NOT NULL)");
            s.execute("CREATE TABLE returns (player TEXT PRIMARY KEY, world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, "
                    + "z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, mode TEXT NOT NULL)");
            s.execute("CREATE TRIGGER stop_here BEFORE INSERT ON paid WHEN NEW.amount=250.5 BEGIN SELECT RAISE(ABORT, 'stopped'); END");
            s.execute("PRAGMA user_version=1");
        }
        Store first = new Store(dir, LOG);
        assertThrows(SQLException.class, () -> first.open(3));
        assertEquals(OLD_FILE, Files.readString(dir.resolve("data.yml")));
        try (Connection c = DriverManager.getConnection(url()); Statement s = c.createStatement()) {
            try (var rs = s.executeQuery("SELECT (SELECT COUNT(*) FROM paid) + (SELECT COUNT(*) FROM meta)")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "nothing was kept from the half import");
            }
            s.execute("DROP TRIGGER stop_here");
        }
        Store second = open();
        assertEquals(1250.5, second.current(), 1e-9);
        assertEquals(2, second.paid().size());
        assertTrue(Files.exists(dir.resolve("data.yml.migrated")));
        second.close();
    }

    @Test
    void aDamagedDatabaseIsNotReplacedByAnEmptyOne() throws Exception {
        open().close();
        byte[] garbage = "this is not a database, not at all, not one bit".getBytes();
        Files.write(dir.resolve("bastion.db"), garbage);
        Store damaged = new Store(dir, LOG);
        assertThrows(Db.CorruptException.class, () -> damaged.open(3));
        assertTrue(Arrays.equals(garbage, Files.readAllBytes(dir.resolve("bastion.db"))));
    }

    @Test
    void aDamagedDatabaseComesBackFromTheNewestGoodBackup() throws Exception {
        Store store = open();
        assertTrue(store.addFunds(STEVE, 300, null));
        assertTrue(store.backup());
        store.close();

        Files.write(dir.resolve("bastion.db"), "garbage garbage garbage garbage".getBytes());
        Files.deleteIfExists(dir.resolve("bastion.db-wal"));
        Store restored = open();
        assertEquals(300, restored.current(), 1e-9);
        restored.close();
    }

    @Test
    void aDatabaseFromANewerVersionIsNotTouched() throws Exception {
        open().close();
        try (Connection c = DriverManager.getConnection(url()); Statement s = c.createStatement()) {
            s.execute("PRAGMA user_version=99");
        }
        byte[] before = Files.readAllBytes(dir.resolve("bastion.db"));
        Store store = new Store(dir, LOG);
        assertThrows(Db.NewerSchemaException.class, () -> store.open(3));
        assertTrue(Arrays.equals(before, Files.readAllBytes(dir.resolve("bastion.db"))));
    }

    @Test
    void aContributionAndItsRecordAreWrittenTogether() throws Exception {
        Store store = open();
        String record = store.journal().begin("contribution", "player=Steve amount=100");
        assertTrue(store.addFunds(STEVE, 100, record));
        store.close();

        Store again = open();
        assertTrue(again.journal().unknown().isEmpty(), "the record was finished with the funding");
        assertEquals(100, again.current(), 1e-9);
        again.close();
    }

    @Test
    void aContributionThatWasCutShortIsFlaggedNotRepeated() throws Exception {
        Store store = open();
        store.journal().begin("contribution", "player=Steve amount=100");
        store.close(); // stopped after the money was taken and before the funding was written

        Store again = open();
        assertEquals(1, again.journal().unknown().size());
        assertEquals(0, again.current(), 1e-9);
        again.close();
    }

    @Test
    void refundsAreRecordedBeforeTheFundingIsCleared() throws Exception {
        Store store = open();
        assertTrue(store.addFunds(STEVE, 100, null));
        assertTrue(store.addFunds(ALEX, 40, null));
        Map<UUID, Double> owed = new LinkedHashMap<>(store.paid());
        Map<UUID, String> records = store.clearFundingWithRefunds(owed);
        assertEquals(2, records.size());
        assertEquals(0, store.current(), 1e-9);
        assertTrue(store.paid().isEmpty());
        store.close(); // stopped before any refund was paid

        Store again = open();
        assertEquals(0, again.current(), 1e-9, "the funding is not there to be refunded a second time");
        assertTrue(again.paid().isEmpty());
        assertEquals(2, again.journal().unknown().size(), "both refunds are listed for a person to check");
        again.close();
    }

    @Test
    void theServerIdIsKeptInTheDatabase() throws Exception {
        Store store = open();
        assertNull(store.serverIdSlot().read());
        store.serverIdSlot().write("0a1b2c3d-1111-2222-3333-444455556666");
        store.close();
        Store again = open();
        assertEquals("0a1b2c3d-1111-2222-3333-444455556666", again.serverIdSlot().read());
        again.close();
    }

    @Test
    void anUnreadableSetupFileIsNeverWrittenOver() throws Exception {
        File setup = dir.resolve("setup.yml").toFile();
        String broken = "regions: [unclosed\n";
        Files.writeString(setup.toPath(), broken);
        YamlConfiguration read = SetupFile.read(setup, LOG);
        assertTrue(read.getKeys(false).isEmpty());
        assertTrue(SetupFile.blocked(setup));
        assertNull(SetupFile.open(setup, "doors", LOG));
        assertEquals(broken, Files.readString(setup.toPath()));
    }

    @Test
    void aDamagedSetupFileComesBackFromItsBackup() throws Exception {
        File setup = dir.resolve("setup.yml").toFile();
        YamlConfiguration first = new YamlConfiguration();
        first.set("regions.spawn.world", "world");
        SetupFile.save(setup, first, LOG);
        YamlConfiguration second = new YamlConfiguration();
        second.set("regions.spawn.world", "world");
        second.set("doors.gate.world", "world");
        SetupFile.save(setup, second, LOG);
        assertTrue(Files.exists(dir.resolve("setup.yml.bak")));

        Files.writeString(setup.toPath(), "regions: [unclosed\n");
        YamlConfiguration read = SetupFile.read(setup, LOG);
        assertFalse(SetupFile.blocked(setup));
        assertNotNull(read.getConfigurationSection("regions"));
    }
}
