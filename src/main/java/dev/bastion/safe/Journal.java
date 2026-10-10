package dev.bastion.safe;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * A record of money and reward payouts. An entry is written (and committed) before the payout is made and finished
 * afterwards. An entry still pending at startup means the server stopped in the middle: it is marked "unknown" and
 * a person decides, because running it again could pay twice.
 */
public final class Journal {

    private final Db db;

    public Journal(Db db) throws SQLException {
        this.db = db;
        db.tx(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS op_journal (id TEXT PRIMARY KEY, kind TEXT NOT NULL, state TEXT NOT NULL, "
                    + "detail TEXT, started_at INTEGER NOT NULL, finished_at INTEGER)");
            }
        });
    }

    /** Writes the entry as pending and returns its id. Call this before the payout. */
    public String begin(String kind, String detail) throws SQLException {
        String id = UUID.randomUUID().toString();
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO op_journal (id, kind, state, detail, started_at) VALUES (?,?,'pending',?,?)")) {
                ps.setString(1, id);
                ps.setString(2, kind);
                ps.setString(3, detail);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
        });
        return id;
    }

    public void succeeded(String id) throws SQLException {
        finish(id, "succeeded", null);
    }

    public void failed(String id, String reason) throws SQLException {
        finish(id, "failed", reason);
    }

    /** The payout is known to have gone wrong and a person has to sort it out: it is listed like an interrupted one. */
    public void flag(String id, String reason) throws SQLException {
        finish(id, "unknown", reason);
    }

    private void finish(String id, String state, String reason) throws SQLException {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE op_journal SET state=?, finished_at=?, detail=COALESCE(detail,'') || ? WHERE id=? AND state='pending'")) {
                ps.setString(1, state);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, reason == null ? "" : " (" + reason + ")");
                ps.setString(4, id);
                ps.executeUpdate();
            }
        });
    }

    /** At startup: entries left pending become unknown. Returns how many entries need a person's review. */
    public int recover(Logger log) throws SQLException {
        db.tx(c -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE op_journal SET state='unknown', finished_at=" + System.currentTimeMillis() + " WHERE state='pending'");
                s.executeUpdate("DELETE FROM op_journal WHERE state='succeeded' AND finished_at < " + (System.currentTimeMillis() - 30L * 86_400_000L));
            }
        });
        List<String> unknown = unknownDetailed();
        for (String line : unknown)
            log.severe("A payout may or may not have been made, and was not repeated: " + line + " - check it by hand, then resolve it with the doctor command.");
        return unknown.size();
    }

    /** Ids, kinds and times only. Details are in the database and are never printed by the doctor command. */
    public List<String> unknown() throws SQLException {
        return db.read(c -> {
            List<String> out = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, kind, started_at FROM op_journal WHERE state='unknown' ORDER BY started_at")) {
                while (rs.next())
                    out.add(rs.getString(1) + " " + rs.getString(2) + " at " + java.time.Instant.ofEpochMilli(rs.getLong(3)));
            }
            return out;
        });
    }

    /** Like {@link #unknown} with what the plugin noted about each one. For the console only, never for the doctor command. */
    private List<String> unknownDetailed() throws SQLException {
        return db.read(c -> {
            List<String> out = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, kind, started_at, detail FROM op_journal WHERE state='unknown' ORDER BY started_at")) {
                while (rs.next())
                    out.add(rs.getString(1) + " " + rs.getString(2) + " at " + java.time.Instant.ofEpochMilli(rs.getLong(3))
                        + (rs.getString(4) == null ? "" : " [" + rs.getString(4) + "]"));
            }
            return out;
        });
    }

    /** A person has looked at it. */
    public boolean resolve(String id) throws SQLException {
        return db.txResult(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE op_journal SET state='reviewed' WHERE id=? AND state='unknown'")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }
}
