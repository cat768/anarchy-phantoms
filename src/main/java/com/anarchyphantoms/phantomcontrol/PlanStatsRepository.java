package com.anarchyphantoms.phantomcontrol;

import com.djrapitops.plan.query.QueryService;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists {@link PhantomStatsTracker}'s counters/timestamps into a table
 * inside Plan Player Analytics' own database, rather than a separate SQLite
 * file (or any other storage) of this plugin's own. This is deliberate:
 *  - AnarchyPhantoms otherwise has zero database footprint, and Plan (when
 *    installed) is already configured with a working, admin-chosen database
 *    connection (SQLite or MySQL) - reusing it via Plan's Query API means
 *    server owners never type a second set of DB credentials anywhere for
 *    this plugin, and there's no second connection pool/file to manage,
 *    back up, or go stale.
 *  - This exact pattern - a plugin creating its own table inside Plan's
 *    database via QueryService - is Plan's own documented approach for
 *    plugins that don't already have their own storage; see
 *    https://github.com/plan-player-analytics/Plan/wiki/Query-API-Getting-started
 *    (the ViaVersion extension linked from there does the same thing this
 *    class does: one table, owned entirely by that one plugin).
 *
 * Isolated in its own class for the same reason as PlanHook and
 * AnarchyPhantomsDataExtension (see PlanHook's class javadoc): every
 * com.djrapitops.plan.* import must live only in classes that are never
 * loaded/verified unless Plan is actually present, or a server without
 * Plan installed would hit NoClassDefFoundError just from this plugin
 * loading normally.
 *
 * Every write goes through {@link QueryService#execute}, which Plan runs
 * asynchronously on its own DB thread(s). Nothing here ever calls
 * Future#get() to wait on that - PhantomStatsTracker's record* methods run
 * on hot call sites (including Folia per-entity/per-region scheduler
 * threads), so this class fires each write and returns immediately;
 * failures are logged and otherwise swallowed; a missed stats write is
 * never worth risking a stall in actual phantom spawn/behavior logic.
 * The one exception is {@link #loadAllPlayerCounters()}/{@link #loadServerTotals()},
 * which intentionally block via {@link QueryService#query} - see their
 * javadoc for why that's safe.
 */
final class PlanStatsRepository implements PhantomStatsTracker.StatsPersistenceSink {

    private static final Logger LOGGER = Logger.getLogger(PlanStatsRepository.class.getName());

    private static final String TABLE = "ap_phantom_stats";

    /**
     * Single-row table for the handful of server-wide totals that have no
     * per-player attribution (see {@link #onEndSpawnerVetoed}). Kept as its
     * own tiny table rather than a sentinel row in {@link #TABLE}: the two
     * tables have genuinely different shapes (one row ever vs. one row per
     * player), and a sentinel-UUID row would need every per-player query
     * below to filter it back out.
     */
    private static final String TOTALS_TABLE = "ap_phantom_server_totals";
    private static final int TOTALS_ROW_ID = 1;

    private final QueryService queryService;

    PlanStatsRepository(QueryService queryService) {
        this.queryService = queryService;
        createTablesIfMissing();
    }

    /**
     * CREATE TABLE syntax differs between SQLite and MySQL (Plan's two
     * supported backends) for auto-increment/primary-key declarations -
     * QueryService#getDBType() is Plan's documented way to branch on this,
     * matching the pattern used in Plan's own Query API tutorial.
     */
    private void createTablesIfMissing() {
        boolean sqlite = "SQLITE".equalsIgnoreCase(queryService.getDBType());

        String playerTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "uuid varchar(36) NOT NULL PRIMARY KEY," +
                "phantoms_spawned_nearby bigint NOT NULL DEFAULT 0," +
                "provocations bigint NOT NULL DEFAULT 0," +
                "location_pick_misses bigint NOT NULL DEFAULT 0," +
                "first_spawned_nearby_millis bigint NOT NULL DEFAULT 0," +
                "last_spawned_nearby_millis bigint NOT NULL DEFAULT 0," +
                "first_provocation_millis bigint NOT NULL DEFAULT 0," +
                "last_provocation_millis bigint NOT NULL DEFAULT 0," +
                "last_location_pick_miss_millis bigint NOT NULL DEFAULT 0" +
                ')';
        queryService.execute(playerTableSql, PreparedStatement::execute);

        String totalsTableSql = "CREATE TABLE IF NOT EXISTS " + TOTALS_TABLE + " (" +
                "id int " + (sqlite ? "PRIMARY KEY" : "NOT NULL") + ',' +
                "total_end_spawner_vetoed_spawns bigint NOT NULL DEFAULT 0," +
                "total_location_pick_misses bigint NOT NULL DEFAULT 0," +
                "total_location_pick_attempts bigint NOT NULL DEFAULT 0" +
                (sqlite ? "" : ",PRIMARY KEY (id)") +
                ')';
        queryService.execute(totalsTableSql, PreparedStatement::execute);

        // Seed the single totals row so every later write can be a plain
        // UPDATE (matching what the per-player upserts below fall back to
        // on the update side) instead of needing its own insert-or-update
        // branch every time.
        String seedTotalsSql = "INSERT INTO " + TOTALS_TABLE + " (id) VALUES (?)"
                + (sqlite ? " ON CONFLICT(id) DO NOTHING" : " ON DUPLICATE KEY UPDATE id = id");
        queryService.execute(seedTotalsSql, statement -> {
            statement.setInt(1, TOTALS_ROW_ID);
            statement.execute();
        });
    }

    // ---- Writes (StatsPersistenceSink) ----
    //
    // Each per-player write is an upsert: INSERT the row with today's event
    // already applied, falling back to an UPDATE that adds the same delta
    // to the existing row if the player already has one. Native upsert
    // syntax (ON CONFLICT / ON DUPLICATE KEY) is used rather than a
    // SELECT-then-branch round trip, since both of Plan's supported
    // backends (SQLite, MySQL) support it and it avoids a race between two
    // events for the same brand-new player arriving concurrently.

    @Override
    public void onEndSpawnerSuccess(UUID playerId, long atMillis) {
        boolean sqlite = "SQLITE".equalsIgnoreCase(queryService.getDBType());
        String sql = "INSERT INTO " + TABLE +
                " (uuid, phantoms_spawned_nearby, first_spawned_nearby_millis, last_spawned_nearby_millis)" +
                " VALUES (?, 1, ?, ?)" +
                (sqlite
                        ? " ON CONFLICT(uuid) DO UPDATE SET" +
                        " phantoms_spawned_nearby = phantoms_spawned_nearby + 1," +
                        " last_spawned_nearby_millis = ?," +
                        " first_spawned_nearby_millis = CASE WHEN first_spawned_nearby_millis = 0 THEN ? ELSE first_spawned_nearby_millis END"
                        : " ON DUPLICATE KEY UPDATE" +
                        " phantoms_spawned_nearby = phantoms_spawned_nearby + 1," +
                        " last_spawned_nearby_millis = VALUES(last_spawned_nearby_millis)," +
                        " first_spawned_nearby_millis = CASE WHEN first_spawned_nearby_millis = 0 THEN VALUES(first_spawned_nearby_millis) ELSE first_spawned_nearby_millis END");

        execute(sql, statement -> {
            statement.setString(1, playerId.toString());
            statement.setLong(2, atMillis);
            statement.setLong(3, atMillis);
            if (sqlite) {
                statement.setLong(4, atMillis);
                statement.setLong(5, atMillis);
            }
            statement.execute();
        });
    }

    @Override
    public void onEndSpawnerVetoed() {
        String sql = "UPDATE " + TOTALS_TABLE +
                " SET total_end_spawner_vetoed_spawns = total_end_spawner_vetoed_spawns + 1 WHERE id = ?";
        execute(sql, statement -> {
            statement.setInt(1, TOTALS_ROW_ID);
            statement.execute();
        });
    }

    @Override
    public void onLocationPickMiss(UUID playerId, long atMillis) {
        boolean sqlite = "SQLITE".equalsIgnoreCase(queryService.getDBType());
        String sql = "INSERT INTO " + TABLE +
                " (uuid, location_pick_misses, last_location_pick_miss_millis)" +
                " VALUES (?, 1, ?)" +
                (sqlite
                        ? " ON CONFLICT(uuid) DO UPDATE SET" +
                        " location_pick_misses = location_pick_misses + 1," +
                        " last_location_pick_miss_millis = ?"
                        : " ON DUPLICATE KEY UPDATE" +
                        " location_pick_misses = location_pick_misses + 1," +
                        " last_location_pick_miss_millis = VALUES(last_location_pick_miss_millis)");

        execute(sql, statement -> {
            statement.setString(1, playerId.toString());
            statement.setLong(2, atMillis);
            if (sqlite) {
                statement.setLong(3, atMillis);
            }
            statement.execute();
        });

        String totalsSql = "UPDATE " + TOTALS_TABLE +
                " SET total_location_pick_misses = total_location_pick_misses + 1," +
                " total_location_pick_attempts = total_location_pick_attempts + 1" +
                " WHERE id = ?";
        execute(totalsSql, statement -> {
            statement.setInt(1, TOTALS_ROW_ID);
            statement.execute();
        });
    }

    @Override
    public void onProvocation(UUID playerId, long atMillis) {
        boolean sqlite = "SQLITE".equalsIgnoreCase(queryService.getDBType());
        String sql = "INSERT INTO " + TABLE +
                " (uuid, provocations, first_provocation_millis, last_provocation_millis)" +
                " VALUES (?, 1, ?, ?)" +
                (sqlite
                        ? " ON CONFLICT(uuid) DO UPDATE SET" +
                        " provocations = provocations + 1," +
                        " last_provocation_millis = ?," +
                        " first_provocation_millis = CASE WHEN first_provocation_millis = 0 THEN ? ELSE first_provocation_millis END"
                        : " ON DUPLICATE KEY UPDATE" +
                        " provocations = provocations + 1," +
                        " last_provocation_millis = VALUES(last_provocation_millis)," +
                        " first_provocation_millis = CASE WHEN first_provocation_millis = 0 THEN VALUES(first_provocation_millis) ELSE first_provocation_millis END");

        execute(sql, statement -> {
            statement.setString(1, playerId.toString());
            statement.setLong(2, atMillis);
            statement.setLong(3, atMillis);
            if (sqlite) {
                statement.setLong(4, atMillis);
                statement.setLong(5, atMillis);
            }
            statement.execute();
        });
        // total_provocations has no separate write here: it's derived by
        // summing the provocations column across every player row at load
        // time instead (see loadServerTotals()), since - unlike vetoed
        // spawns and location-pick misses - it's fully recoverable that
        // way and keeping a second copy would risk it drifting from the
        // per-player rows it's supposed to mirror.
    }

    // ---- Reads (startup hydration) ----

    /**
     * Loads every persisted player's counters/timestamps, keyed by UUID.
     * Called once by PlanHook during startup hydration. Blocking (via
     * QueryService#query) is acceptable here specifically because this
     * runs exactly once, synchronously, during plugin enable - before any
     * player has joined and before any Folia region task that depends on
     * this tracker has started - never from a later hot path.
     */
    Map<UUID, PhantomStatsTracker.PersistedPlayerCounters> loadAllPlayerCounters() {
        String sql = "SELECT * FROM " + TABLE;
        return queryService.query(sql, statement -> {
            Map<UUID, PhantomStatsTracker.PersistedPlayerCounters> result = new HashMap<>();
            try (ResultSet set = statement.executeQuery()) {
                while (set.next()) {
                    UUID uuid = UUID.fromString(set.getString("uuid"));
                    result.put(uuid, new PhantomStatsTracker.PersistedPlayerCounters(
                            set.getLong("phantoms_spawned_nearby"),
                            set.getLong("provocations"),
                            set.getLong("location_pick_misses"),
                            0L, // activeNearby is intentionally never persisted - see PhantomStatsTracker#reportActiveNearby
                            set.getLong("first_spawned_nearby_millis"),
                            set.getLong("last_spawned_nearby_millis"),
                            set.getLong("first_provocation_millis"),
                            set.getLong("last_provocation_millis"),
                            set.getLong("last_location_pick_miss_millis")
                    ));
                }
            }
            return result;
        });
    }

    /**
     * Loads the server-wide totals: total_end_spawner_spawns and
     * total_provocations are derived by summing their per-player columns
     * (see onProvocation's javadoc for why); the remaining totals have no
     * per-player attribution and are read directly from the totals row.
     */
    PhantomStatsTracker.PersistedServerTotals loadServerTotals() {
        String sumSql = "SELECT" +
                " SUM(phantoms_spawned_nearby) AS total_spawns," +
                " SUM(provocations) AS total_provocations" +
                " FROM " + TABLE;
        long[] sums = queryService.query(sumSql, statement -> {
            try (ResultSet set = statement.executeQuery()) {
                if (!set.next()) {
                    return new long[]{0L, 0L};
                }
                // SUM() over zero rows returns SQL NULL, not 0, on both
                // SQLite and MySQL - getLong() already maps SQL NULL to
                // Java 0L, so no explicit wasNull() check is needed here.
                return new long[]{set.getLong("total_spawns"), set.getLong("total_provocations")};
            }
        });

        String totalsSql = "SELECT * FROM " + TOTALS_TABLE + " WHERE id = ?";
        long[] rowTotals = queryService.query(totalsSql, statement -> {
            statement.setInt(1, TOTALS_ROW_ID);
            try (ResultSet set = statement.executeQuery()) {
                if (!set.next()) {
                    return new long[]{0L, 0L, 0L};
                }
                return new long[]{
                        set.getLong("total_end_spawner_vetoed_spawns"),
                        set.getLong("total_location_pick_misses"),
                        set.getLong("total_location_pick_attempts")
                };
            }
        });

        return new PhantomStatsTracker.PersistedServerTotals(
                sums[0],
                rowTotals[0],
                sums[1],
                rowTotals[1],
                rowTotals[2]
        );
    }

    // ---- Plumbing ----

    /**
     * Thin wrapper around {@link QueryService#execute}, taking the same
     * {@link QueryService.ThrowingConsumer}&lt;PreparedStatement&gt; shape
     * (see QueryService's javadoc: {@code execute(String, ThrowingConsumer<PreparedStatement>)})
     * so every write method above can pass a plain lambda that sets
     * parameters and calls statement.execute()/executeUpdate(), exactly
     * like Plan's own Query API tutorial examples, without each one
     * needing its own try/catch for the checked SQLException those calls
     * throw. This method is the single place that catches it - logging a
     * write failure instead of letting it vanish into whatever Plan's
     * async executor does with an uncaught exception, since
     * PhantomStatsTracker's call sites (see its class javadoc) have no way
     * to surface that failure themselves.
     */
    private void execute(String sql, QueryService.ThrowingConsumer<PreparedStatement> statementConsumer) {
        queryService.execute(sql, statement -> {
            try {
                statementConsumer.accept(statement);
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "[AnarchyPhantoms] Failed to write phantom stats to Plan's database.", e);
            }
        });
    }
}