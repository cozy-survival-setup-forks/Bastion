package dev.bastion.dungeon;

import dev.bastion.safe.Db;
import dev.bastion.safe.DbBackups;
import dev.bastion.safe.Health;
import dev.bastion.safe.Journal;
import dev.bastion.safe.SafeIo;
import dev.bastion.safe.ServerId;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * What has to survive a restart, kept in {@code bastion.db} (SQLite): the funding so far, who paid what (for refunds),
 * whether a run was in progress, and where every player in the dungeon came from. Every change is written in one
 * transaction before the call returns. The older {@code data.yml} is brought in once, in a single transaction, and
 * checked. The money that leaves a player and the refunds are written to a payout record around the economy calls.
 */
public final class Store {

    public static final int SCHEMA = 1;
    private static final String BACKUP_PREFIX = "bastion";

    public record Return(String world, double x, double y, double z, float yaw, float pitch, GameMode mode) {
        public Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    private final Logger log;
    private final File legacy;
    private final Path dbFile;
    private final Path backupDir;

    private double current;
    private boolean runActive;
    private String lastResult = "NONE";
    private String lastBoss = "";
    private final Map<UUID, Double> paid = new LinkedHashMap<>();
    private final Map<UUID, Return> returns = new HashMap<>();

    private Db db;
    private Journal journal;
    private DbBackups backups;
    private boolean imported;

    public Store(Plugin plugin) {
        this(plugin.getDataFolder().toPath(), plugin.getLogger());
    }

    public Store(Path folder, Logger log) {
        this.log = log;
        this.legacy = folder.resolve("data.yml").toFile();
        this.dbFile = folder.resolve("bastion.db");
        this.backupDir = folder.resolve("backups");
    }

    /**
     * Opens the database (a damaged one is replaced by the newest backup that verifies), brings in an old data.yml
     * once, and reads everything. Anything that cannot be used throws, so the plugin can stop instead of starting empty.
     */
    public void open(int backupKeep) throws IOException, SQLException, InvalidConfigurationException {
        db = Db.openRecovering(dbFile, SCHEMA, backupDir, BACKUP_PREFIX, true, log);
        try {
            db.tx(c -> {
                try (Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS paid (player TEXT PRIMARY KEY, amount REAL NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS returns (player TEXT PRIMARY KEY, world TEXT NOT NULL, x REAL NOT NULL, "
                            + "y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, mode TEXT NOT NULL)");
                }
            });
            if (db.userVersion() < SCHEMA)
                db.setUserVersion(SCHEMA);
            journal = new Journal(db);
            journal.recover(log);
            importLegacy();
            readAll();
        } catch (IOException | SQLException | InvalidConfigurationException | RuntimeException e) {
            db.close();
            db = null;
            throw e;
        }
        configureBackups(backupKeep);
        Health.file("bastion.db", imported ? "in use, data.yml was brought in" : "in use");
    }

    public void configureBackups(int keep) {
        if (db != null)
            backups = new DbBackups(db, backupDir, BACKUP_PREFIX, keep, log);
    }

    public boolean backup() {
        return backups == null || backups.run();
    }

    public Journal journal() {
        return journal;
    }

    public int schemaVersion() {
        try {
            return db == null ? SCHEMA : db.userVersion();
        } catch (SQLException e) {
            return -1;
        }
    }

    public String newestBackup() {
        List<Path> found = DbBackups.list(backupDir, BACKUP_PREFIX);
        return found.isEmpty() ? null : found.get(0).getFileName().toString();
    }

    public Path databaseFile() {
        return dbFile;
    }

    public ServerId.Slot serverIdSlot() {
        return new ServerId.Slot() {
            @Override
            public String read() throws SQLException {
                return db.read(c -> metaValue(c, "server-id"));
            }

            @Override
            public void write(String id) throws SQLException {
                db.tx(c -> putMeta(c, "server-id", id));
            }
        };
    }

    // ---------------------------------------------------------------- funding

    public double current() {
        return current;
    }

    /**
     * Adds a contribution that has already been taken from the player. The funding and the record of the payout are
     * written together. If that fails nothing changes and false is returned, so the caller gives the money back.
     *
     * @param record the id of the payout record to finish, or null
     */
    public boolean addFunds(UUID who, double amount, String record) {
        double beforeCurrent = current;
        Double beforePaid = paid.get(who);
        current += amount;
        paid.merge(who, amount, Double::sum);
        if (persist(record, null)) return true;
        current = beforeCurrent;
        if (beforePaid == null) paid.remove(who);
        else paid.put(who, beforePaid);
        return false;
    }

    public void setCurrent(double value) {
        current = value;
        persist(null, null);
    }

    public Map<UUID, Double> paid() {
        return Collections.unmodifiableMap(paid);
    }

    /** Forgets the funding, after it has been spent on a run. */
    public void clearFunding() {
        current = 0;
        paid.clear();
        persist(null, null);
    }

    /**
     * Forgets the funding and, in the same transaction, writes one pending record for each refund that is now owed.
     * The funding is gone before any money moves, so a stop in the middle can never pay anyone twice; a refund that
     * was not finished is listed for a person to check.
     *
     * @return the record id of each player's refund; empty when the records could not be written (nothing changed)
     */
    public Map<UUID, String> clearFundingWithRefunds(Map<UUID, Double> refunds) {
        double beforeCurrent = current;
        Map<UUID, Double> beforePaid = new LinkedHashMap<>(paid);
        current = 0;
        paid.clear();
        Map<UUID, String> ids = new LinkedHashMap<>();
        for (UUID id : refunds.keySet()) ids.put(id, UUID.randomUUID().toString());
        if (persist(null, ids.isEmpty() ? null : refundRows(ids, refunds))) return ids;
        current = beforeCurrent;
        paid.putAll(beforePaid);
        return Map.of();
    }

    private static List<String[]> refundRows(Map<UUID, String> ids, Map<UUID, Double> refunds) {
        List<String[]> rows = new java.util.ArrayList<>();
        ids.forEach((player, id) -> rows.add(new String[]{id, "refund", "player=" + player + " amount=" + refunds.get(player)}));
        return rows;
    }

    public boolean runActive() {
        return runActive;
    }

    public void runActive(boolean active) {
        runActive = active;
        persist(null, null);
    }

    public void result(String result, String boss) {
        lastResult = result;
        lastBoss = boss;
        persist(null, null);
    }

    public String lastResult() {
        return lastResult;
    }

    public String lastBoss() {
        return lastBoss;
    }

    // ---------------------------------------------------------------- where players came from

    public void remember(UUID id, Location at, GameMode mode) {
        returns.put(id, new Return(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(), mode));
        persist(null, null);
    }

    public Return returnOf(UUID id) {
        return returns.get(id);
    }

    public void forget(UUID id) {
        if (returns.remove(id) != null) persist(null, null);
    }

    public boolean hasReturns() {
        return !returns.isEmpty();
    }

    // ---------------------------------------------------------------- the database

    /** Writes the whole state in one transaction (it is small). With a record id, that payout is marked as done in the same step. */
    private boolean persist(String finishRecord, List<String[]> newRecords) {
        if (db == null) return true;
        try {
            db.tx(c -> {
                writeState(c);
                if (finishRecord != null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE op_journal SET state='succeeded', finished_at=? WHERE id=? AND state='pending'")) {
                        ps.setLong(1, System.currentTimeMillis());
                        ps.setString(2, finishRecord);
                        ps.executeUpdate();
                    }
                }
                if (newRecords != null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO op_journal (id, kind, state, detail, started_at) VALUES (?,?,'pending',?,?)")) {
                        for (String[] row : newRecords) {
                            ps.setString(1, row[0]);
                            ps.setString(2, row[1]);
                            ps.setString(3, row[2]);
                            ps.setLong(4, System.currentTimeMillis());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
            });
            return true;
        } catch (SQLException e) {
            log.severe("Could not save bastion.db: " + e.getMessage());
            Health.failure("bastion.db could not be saved: " + e.getMessage());
            return false;
        }
    }

    /** The whole state, written inside the transaction of the caller. */
    private void writeState(Connection c) throws SQLException {
        putMeta(c, "current", Double.toString(current));
        putMeta(c, "run_active", runActive ? "1" : "0");
        putMeta(c, "last_result", lastResult);
        putMeta(c, "last_boss", lastBoss);
        try (Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM paid");
            s.executeUpdate("DELETE FROM returns");
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO paid (player, amount) VALUES (?,?)")) {
            for (Map.Entry<UUID, Double> e : paid.entrySet()) {
                ps.setString(1, e.getKey().toString());
                ps.setDouble(2, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO returns (player, world, x, y, z, yaw, pitch, mode) VALUES (?,?,?,?,?,?,?,?)")) {
            for (Map.Entry<UUID, Return> e : returns.entrySet()) {
                Return r = e.getValue();
                ps.setString(1, e.getKey().toString());
                ps.setString(2, r.world());
                ps.setDouble(3, r.x());
                ps.setDouble(4, r.y());
                ps.setDouble(5, r.z());
                ps.setDouble(6, r.yaw());
                ps.setDouble(7, r.pitch());
                ps.setString(8, r.mode().name());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void readAll() throws SQLException {
        paid.clear();
        returns.clear();
        db.read(c -> {
            String value = metaValue(c, "current");
            current = value == null ? 0 : parseDouble(value);
            runActive = "1".equals(metaValue(c, "run_active"));
            String result = metaValue(c, "last_result");
            lastResult = result == null ? "NONE" : result;
            String boss = metaValue(c, "last_boss");
            lastBoss = boss == null ? "" : boss;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT player, amount FROM paid")) {
                while (rs.next()) paid.put(UUID.fromString(rs.getString(1)), rs.getDouble(2));
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT player, world, x, y, z, yaw, pitch, mode FROM returns")) {
                while (rs.next()) {
                    try {
                        returns.put(UUID.fromString(rs.getString(1)), new Return(rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                                rs.getDouble(5), (float) rs.getDouble(6), (float) rs.getDouble(7), GameMode.valueOf(rs.getString(8))));
                    } catch (IllegalArgumentException e) {
                        log.warning("bastion.db: skipping the return place of " + rs.getString(1) + ", " + e.getMessage());
                    }
                }
            }
            return null;
        });
    }

    private static double parseDouble(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- bringing in data.yml

    private void importLegacy() throws IOException, SQLException, InvalidConfigurationException {
        if (!legacy.exists())
            return;
        if (db.read(c -> metaValue(c, "import")) != null) {
            setAside(); // brought in earlier; only the rename was left
            return;
        }
        if (db.read(c -> scalar(c, "SELECT COUNT(*) FROM paid") + scalar(c, "SELECT COUNT(*) FROM returns")) > 0
                || db.read(c -> metaValue(c, "current")) != null) {
            log.severe("data.yml is there, but bastion.db already holds data and was not made from that file. "
                    + "data.yml was left alone and bastion.db is used.");
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(legacy);
        } catch (IOException | InvalidConfigurationException e) {
            // starting empty would lose the funding and the refunds that are owed: the file stays and the plugin stops
            log.severe("data.yml could not be read (" + e.getMessage() + "). It was left where it is, untouched, and Bastion "
                    + "will not start until it is fixed or removed.");
            Health.failure("data.yml could not be read");
            throw e;
        }
        current = y.getDouble("funding.current");
        runActive = y.getBoolean("run-active");
        lastResult = y.getString("last-result", "NONE");
        lastBoss = y.getString("last-boss", "");
        ConfigurationSection c = y.getConfigurationSection("funding.paid");
        if (c != null) {
            for (String k : c.getKeys(false)) {
                try {
                    paid.put(UUID.fromString(k), c.getDouble(k));
                } catch (IllegalArgumentException e) {
                    // this is money that is owed back: not skipped
                    throw new InvalidConfigurationException("funding.paid." + k + " is not a valid player id");
                }
            }
        }
        ConfigurationSection r = y.getConfigurationSection("returns");
        if (r != null) {
            for (String k : r.getKeys(false)) {
                ConfigurationSection s = r.getConfigurationSection(k);
                if (s == null) continue;
                try {
                    returns.put(UUID.fromString(k), new Return(s.getString("world"), s.getDouble("x"), s.getDouble("y"),
                            s.getDouble("z"), (float) s.getDouble("yaw"), (float) s.getDouble("pitch"),
                            GameMode.valueOf(s.getString("mode", "SURVIVAL"))));
                } catch (IllegalArgumentException e) {
                    log.warning("data.yml: skipping returns." + k + ", " + e.getMessage());
                }
            }
        }
        double wantPaid = paid.values().stream().mapToDouble(Double::doubleValue).sum();
        double wantCurrent = current;
        int wantPaidRows = paid.size();
        int wantReturns = returns.size();
        boolean wantRun = runActive;
        try {
            db.tx(conn -> {
                // one transaction: a stop in the middle leaves nothing behind and the next start does all of it again
                writeState(conn);
                long paidRows = scalar(conn, "SELECT COUNT(*) FROM paid");
                double paidSum = scalarDouble(conn, "SELECT COALESCE(SUM(amount),0) FROM paid");
                long returnRows = scalar(conn, "SELECT COUNT(*) FROM returns");
                double cur = parseDouble(metaValue(conn, "current"));
                boolean run = "1".equals(metaValue(conn, "run_active"));
                if (paidRows != wantPaidRows || Math.abs(paidSum - wantPaid) > 1e-6 || returnRows != wantReturns
                        || Math.abs(cur - wantCurrent) > 1e-6 || run != wantRun)
                    throw new SQLException("what was written does not match data.yml (paid " + paidRows + "/" + wantPaidRows
                            + ", amount " + paidSum + "/" + wantPaid + ", returns " + returnRows + "/" + wantReturns
                            + ", current " + cur + "/" + wantCurrent + ")");
                putMeta(conn, "import", "done " + paidRows + " contributions, " + returnRows + " returns");
            });
        } catch (SQLException e) {
            paid.clear();
            returns.clear();
            current = 0;
            runActive = false;
            lastResult = "NONE";
            lastBoss = "";
            throw e;
        }
        imported = true;
        log.info("data.yml was brought into bastion.db and checked: " + paid.size() + " contributions, " + returns.size() + " return places.");
        setAside();
    }

    private void setAside() {
        Path from = legacy.toPath();
        Path to = from.resolveSibling(legacy.getName() + ".migrated");
        if (Files.exists(to)) to = from.resolveSibling(legacy.getName() + ".migrated-" + SafeIo.stamp());
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            log.info("The old data.yml was renamed to " + to.getFileName() + ". It can be removed once you are happy with bastion.db.");
        } catch (IOException e) {
            log.warning("data.yml could not be renamed (" + e.getMessage() + "); it will not be read again.");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String metaValue(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void putMeta(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static double scalarDouble(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getDouble(1) : 0;
        }
    }

    /** Waits for nothing (writes are made before each call returns) and closes the database. Called on shutdown. */
    public void close() {
        if (db != null) {
            db.close();
            db = null;
        }
    }
}
