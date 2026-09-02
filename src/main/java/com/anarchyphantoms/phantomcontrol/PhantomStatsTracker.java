package com.anarchyphantoms.phantomcontrol;

import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory aggregate counters for phantom activity, kept purely so this
 * data can be reported somewhere (currently: the Plan DataExtension hook -
 * see {@link AnarchyPhantomsDataExtension}). Nothing in the plugin's actual
 * spawn/behavior logic reads from this class; it is a write-only sink from
 * their perspective, fed from the same call sites that already produce
 * debug output.
 *
 * In-memory by default, with optional persistence bolted on:
 *  - PhantomProvocationTracker already persists per-entity state via PDC,
 *    which is the state that actually needs to survive a restart (governs
 *    behavior). These counters are display-only aggregates derived from
 *    events as they happen, so losing them on restart was historically
 *    treated as acceptable.
 *  - However, if Plan Player Analytics is installed, {@link PlanHook} wires
 *    a {@link StatsPersistenceSink} into {@link #setPersistenceSink} that
 *    mirrors every counter update into a table inside Plan's own database
 *    (see PlanStatsRepository) - reusing Plan's already-configured DB
 *    connection rather than adding a separate storage dependency to this
 *    plugin. When Plan isn't installed, no sink is set and behavior is
 *    unchanged from before: purely in-memory, "since last restart" numbers.
 *  - {@link PlanHook} also hydrates this tracker from that table on enable
 *    (see {@link #restoreServerTotals} / {@link #restorePlayerCounters}) so
 *    both this plugin's own view and Plan's page agree immediately after a
 *    restart, rather than only converging once fresh events happen.
 *
 * Thread-safety: counters use LongAdder/AtomicLong and the per-player map is
 * a ConcurrentHashMap, since call sites span multiple per-player/per-entity
 * EntityScheduler threads under Folia (see PhantomEndSpawner, PhantomBehaviorListener)
 * as well as Plan's own async extension-method calls. No method here does
 * any Bukkit API access (no entity/world reads), so none of it needs to run
 * on any particular region thread - it's plain counter bookkeeping. The
 * optional persistence sink is called synchronously from these same
 * call sites, so any sink implementation MUST be non-blocking (fire-and-forget
 * its actual I/O) - see PlanStatsRepository, which delegates to Plan's
 * QueryService#execute (async) and never waits on the result here.
 */
public final class PhantomStatsTracker {

    /**
     * Logger sink for {@link #recordLocationPickMiss(Player)}. Deliberately
     * a plain JUL logger obtained by class name rather than routed through
     * AnarchyPhantomsPlugin/PhantomDebugNotifier: this class is a passive
     * counter sink with no reference back to the plugin instance, and unlike
     * PhantomDebugNotifier's chat/debug-gated lines, a location-pick miss is
     * worth a permanent, always-on log trail independent of whether debug
     * mode happens to be on - see recordLocationPickMiss's javadoc for why.
     */
    private static final Logger LOGGER = Logger.getLogger(PhantomStatsTracker.class.getName());

    /**
     * Per-player aggregate counters. Entries are created lazily on first
     * increment and intentionally never removed on quit - a player's
     * lifetime totals should survive them logging off and back on, and the
     * map is bounded by unique players seen, not by anything unbounded.
     */
    private final ConcurrentHashMap<UUID, PlayerCounters> perPlayer = new ConcurrentHashMap<>();

    // Server-wide counters.
    private final LongAdder totalEndSpawnerSpawns = new LongAdder();
    private final LongAdder totalEndSpawnerVetoedSpawns = new LongAdder();
    private final LongAdder totalProvocations = new LongAdder();
    private final LongAdder totalLocationPickMisses = new LongAdder();
    private final LongAdder totalLocationPickAttempts = new LongAdder();

    /**
     * Optional persistence hook, wired up by {@link PlanHook#hookIntoPlan()}
     * only when Plan is actually installed and enabled; null otherwise
     * (the default), in which case this class behaves exactly as it always
     * has - a plain in-memory counter set with no I/O anywhere.
     *
     * Plain volatile rather than an AtomicReference: it's set at most once
     * per plugin lifecycle (from PlanHook, shortly after onEnable/on a Plan
     * reload) and read far more often than written, so a simple
     * publish-once-read-many field is enough - no compare-and-swap or
     * multi-field atomicity is needed here.
     */
    private volatile StatsPersistenceSink persistenceSink;

    /**
     * Wires (or clears, if {@code sink} is null) the optional persistence
     * sink. Called by PlanHook once Plan's DataExtension/QueryService are
     * confirmed available - never called directly by anything that isn't
     * isolating Plan's classes, so this class itself stays free of any
     * Plan import (see PlanHook's class javadoc for why that isolation
     * matters).
     */
    void setPersistenceSink(StatsPersistenceSink sink) {
        this.persistenceSink = sink;
    }

    /**
     * Restores a single player's counters/timestamps from persisted storage.
     * Intended to be called only by PlanHook during startup hydration,
     * before any live gameplay events for this tracker instance have been
     * recorded - it unconditionally overwrites whatever (should be
     * all-zero/default) counters already exist for this player rather than
     * merging, since at hydration time there is nothing legitimate to merge
     * with yet.
     *
     * Does NOT re-invoke the persistence sink (this is loading FROM storage,
     * not a new event TO persist) - callers restoring many players in a
     * batch don't need to worry about redundant write-back per row.
     */
    void restorePlayerCounters(UUID playerId, PersistedPlayerCounters saved) {
        PlayerCounters c = counters(playerId);
        c.phantomsSpawnedNearby.add(saved.phantomsSpawnedNearby());
        c.provocations.add(saved.provocations());
        c.locationPickMisses.add(saved.locationPickMisses());
        c.activeNearby.set(saved.activeNearby());
        c.firstSpawnedNearbyMillis.set(saved.firstSpawnedNearbyMillis());
        c.lastSpawnedNearbyMillis.set(saved.lastSpawnedNearbyMillis());
        c.firstProvocationMillis.set(saved.firstProvocationMillis());
        c.lastProvocationMillis.set(saved.lastProvocationMillis());
        c.lastLocationPickMissMillis.set(saved.lastLocationPickMissMillis());
    }

    /**
     * Restores the server-wide totals from persisted storage. Like
     * {@link #restorePlayerCounters}, intended to run once at startup before
     * any live events land on this tracker instance, and does not re-invoke
     * the persistence sink.
     */
    void restoreServerTotals(PersistedServerTotals saved) {
        totalEndSpawnerSpawns.add(saved.totalEndSpawnerSpawns());
        totalEndSpawnerVetoedSpawns.add(saved.totalEndSpawnerVetoedSpawns());
        totalProvocations.add(saved.totalProvocations());
        totalLocationPickMisses.add(saved.totalLocationPickMisses());
        totalLocationPickAttempts.add(saved.totalLocationPickAttempts());
    }

    /**
     * Called by PhantomEndSpawner right after a successful active-spawn
     * (the phantom passed PhantomSpawnListener's veto check and is alive in
     * the world), attributing it to the player whose presence triggered it.
     */
    public void recordEndSpawnerSuccess(Player nearPlayer) {
        totalEndSpawnerSpawns.increment();
        totalLocationPickAttempts.increment();
        UUID playerId = nearPlayer.getUniqueId();
        PlayerCounters c = counters(playerId);
        c.phantomsSpawnedNearby.increment();
        long now = System.currentTimeMillis();
        c.lastSpawnedNearbyMillis.set(now);
        c.firstSpawnedNearbyMillis.compareAndSet(0L, now);

        StatsPersistenceSink sink = persistenceSink;
        if (sink != null) {
            sink.onEndSpawnerSuccess(playerId, now);
        }
    }

    /**
     * Called by PhantomEndSpawner when world.spawn(...) was vetoed (almost
     * always by PhantomSpawnListener's surface/dimension checks). Tracked
     * separately from successes so a success-rate stat can be derived
     * without conflating "we didn't attempt" with "we attempted and were
     * denied".
     */
    public void recordEndSpawnerVetoed() {
        totalEndSpawnerVetoedSpawns.increment();

        StatsPersistenceSink sink = persistenceSink;
        if (sink != null) {
            sink.onEndSpawnerVetoed();
        }
    }

    /**
     * Called by PhantomEndSpawner every time {@code pickSpawnLocation}
     * exhausts all of its sampled columns without finding an allowed
     * surface block (the "pickSpawnLocation returned null (no allowed
     * surface block found in the sampled column)" condition).
     *
     * This is distinct from {@link #recordEndSpawnerVetoed()}: a veto means
     * we found a spot and attempted world.spawn() but PhantomSpawnListener
     * (or another plugin) rejected it after the fact, whereas a location-pick
     * miss means we never attempted a spawn at all because no valid column
     * was found near the player (e.g. every sampled column landed on a thin
     * bridge, an outer End island with no chorus, or open void). Tracking it
     * separately means an admin can tell "spawns are being rejected" apart
     * from "spawns are rarely even being attempted here" - the latter
     * usually points at allowed-surface-blocks or horizontalSpread tuning
     * rather than at PhantomSpawnListener at all.
     *
     * Recorded in two ways so it's visible regardless of setup:
     *  - A persistent counter (both per-player and server-wide), exposed to
     *    Plan below, so the *rate* of misses is visible on player and server
     *    pages without needing debug mode on or console access at all.
     *  - A single INFO-level line to the server log, unconditionally (not
     *    gated behind debug.enabled the way PhantomDebugNotifier's chat/console
     *    debug line is). This is intentionally NOT a WARNING: missing a
     *    column is an expected, routine outcome on thin bridges/outer
     *    islands, not a malfunction, and logging it at WARNING would train
     *    admins to tune it out. INFO here still guarantees at least one
     *    server-log line per miss exists outside of debug mode, without
     *    implying something is broken.
     */
    public void recordLocationPickMiss(Player player) {
        totalLocationPickMisses.increment();
        totalLocationPickAttempts.increment();
        UUID playerId = player.getUniqueId();
        PlayerCounters c = counters(playerId);
        c.locationPickMisses.increment();
        long now = System.currentTimeMillis();
        c.lastLocationPickMissMillis.set(now);

        LOGGER.log(Level.INFO, "[AnarchyPhantoms] End-spawn location pick missed for {0} "
                        + "(no allowed surface block found in any sampled column near {1}, {2}, {3})",
                new Object[]{
                        player.getName(),
                        player.getLocation().getBlockX(),
                        player.getLocation().getBlockY(),
                        player.getLocation().getBlockZ()
                });

        StatsPersistenceSink sink = persistenceSink;
        if (sink != null) {
            sink.onLocationPickMiss(playerId, now);
        }
    }

    /**
     * Called by PhantomBehaviorListener exactly when a phantom transitions
     * from not-provoked to provoked (i.e. tracker.markProvoked(...) just
     * returned true) - one call per genuinely new provocation, not per hit
     * on an already-hostile phantom.
     */
    public void recordProvocation(Player attacker) {
        totalProvocations.increment();
        UUID playerId = attacker.getUniqueId();
        PlayerCounters c = counters(playerId);
        c.provocations.increment();
        long now = System.currentTimeMillis();
        c.lastProvocationMillis.set(now);
        c.firstProvocationMillis.compareAndSet(0L, now);

        StatsPersistenceSink sink = persistenceSink;
        if (sink != null) {
            sink.onProvocation(playerId, now);
        }
    }

    /** Lifetime count of phantoms actively spawned by PhantomEndSpawner near this player. */
    public long getPhantomsSpawnedNear(UUID playerId) {
        return peek(playerId).phantomsSpawnedNearby.sum();
    }

    /** Lifetime count of phantoms this player has provoked (first-hit only, not repeat attacks). */
    public long getProvocationsBy(UUID playerId) {
        return peek(playerId).provocations.sum();
    }

    /** Server-wide lifetime count of successful PhantomEndSpawner spawns. */
    public long getTotalEndSpawnerSpawns() {
        return totalEndSpawnerSpawns.sum();
    }

    /** Server-wide lifetime count of PhantomEndSpawner attempts vetoed after the fact (surface/dimension checks). */
    public long getTotalEndSpawnerVetoedSpawns() {
        return totalEndSpawnerVetoedSpawns.sum();
    }

    /** Server-wide lifetime count of phantom provocations (new hostile transitions). */
    public long getTotalProvocations() {
        return totalProvocations.sum();
    }

    /** Server-wide lifetime count of pickSpawnLocation exhausting every sampled column with no allowed surface found. */
    public long getTotalLocationPickMisses() {
        return totalLocationPickMisses.sum();
    }

    /** Lifetime count of location-pick misses attributed to this player (i.e. attempted while they were the nearby player). */
    public long getLocationPickMissesFor(UUID playerId) {
        return peek(playerId).locationPickMisses.sum();
    }

    /**
     * Fraction of pickSpawnLocation calls (successful spawn attempts + location-pick
     * misses) that missed entirely, in [0.0, 1.0]. This is the "how often is
     * the End-spawner not even finding a place to try" rate discussed in
     * recordLocationPickMiss's javadoc - kept distinct from
     * getEndSpawnerSuccessRate(), which only covers attempts that got as far
     * as world.spawn(). Returns 0.0 if no attempts have been made yet.
     */
    public double getLocationPickMissRate() {
        long misses = totalLocationPickMisses.sum();
        long attempts = totalLocationPickAttempts.sum();
        if (attempts == 0L) {
            return 0.0;
        }
        return misses / (double) attempts;
    }

    /** Epoch millis of the last time a phantom was actively spawned near this player, or 0 if never. */
    public long getLastSpawnedNearbyMillis(UUID playerId) {
        return peek(playerId).lastSpawnedNearbyMillis.get();
    }

    /** Epoch millis of the first time a phantom was actively spawned near this player, or 0 if never. */
    public long getFirstSpawnedNearbyMillis(UUID playerId) {
        return peek(playerId).firstSpawnedNearbyMillis.get();
    }

    /** Epoch millis of this player's last provocation, or 0 if they've never provoked one. */
    public long getLastProvocationMillis(UUID playerId) {
        return peek(playerId).lastProvocationMillis.get();
    }

    /** Epoch millis of this player's first-ever provocation, or 0 if they've never provoked one. */
    public long getFirstProvocationMillis(UUID playerId) {
        return peek(playerId).firstProvocationMillis.get();
    }

    /** Epoch millis of the last location-pick miss attributed to this player, or 0 if none yet. */
    public long getLastLocationPickMissMillis(UUID playerId) {
        return peek(playerId).lastLocationPickMissMillis.get();
    }

    /**
     * Fraction of attempted End-spawner placements (success + vetoed) that
     * actually resulted in a live phantom, in [0.0, 1.0]. Returns 1.0 if no
     * attempts have been made yet, treating "no data" as "nothing has gone
     * wrong yet" rather than reporting a misleading 0%.
     */
    public double getEndSpawnerSuccessRate() {
        long success = totalEndSpawnerSpawns.sum();
        long vetoed = totalEndSpawnerVetoedSpawns.sum();
        long attempts = success + vetoed;
        if (attempts == 0L) {
            return 1.0;
        }
        return success / (double) attempts;
    }

    /** Live count of phantoms currently spawned/tracked as active near this player, as of the last spawn/despawn this tracker saw. */
    public long getActivePhantomsNear(UUID playerId) {
        return Math.max(0L, peek(playerId).activeNearby.get());
    }

    /**
     * Called by PhantomEndSpawner when it counts nearby phantoms as part of
     * its own per-player-cap check, so the "currently active nearby" figure
     * stays reasonably fresh without a dedicated extra scan solely for
     * Plan's benefit - it piggybacks on a count PhantomEndSpawner already
     * performs every check cycle regardless of whether this tracker exists.
     *
     * Deliberately NOT persisted (see {@link #restorePlayerCounters}, which
     * never touches activeNearby): this is a live, momentary snapshot that
     * changes on essentially every check cycle for every online player, so
     * writing it to Plan's database on every call would add a large,
     * pointless amount of write pressure for a number that's inherently
     * stale the instant the server actually restarts anyway - "active
     * nearby right now" from three hours before a reboot isn't meaningful
     * data to carry forward, unlike the lifetime counters/timestamps above.
     * It naturally starts back at 0 after a restart and refills itself
     * within one check cycle per online player.
     */
    public void reportActiveNearby(Player player, int count) {
        counters(player.getUniqueId()).activeNearby.set(count);
    }

    private PlayerCounters counters(UUID playerId) {
        return perPlayer.computeIfAbsent(playerId, id -> new PlayerCounters());
    }

    /**
     * Read-only lookup that never creates a map entry, so a Plan query for a
     * player this server has never actually tracked activity for (e.g. an
     * alt account, or a player who joined but never entered The End) doesn't
     * bloat the map with permanent all-zero entries.
     */
    private PlayerCounters peek(UUID playerId) {
        PlayerCounters existing = perPlayer.get(playerId);
        return existing != null ? existing : PlayerCounters.EMPTY;
    }

    private static final class PlayerCounters {
        static final PlayerCounters EMPTY = new PlayerCounters();

        final LongAdder phantomsSpawnedNearby = new LongAdder();
        final LongAdder provocations = new LongAdder();
        final LongAdder locationPickMisses = new LongAdder();
        final AtomicLong activeNearby = new AtomicLong(0L);

        // Epoch-millis timestamps, 0L meaning "never happened". Plain
        // AtomicLong (not LongAdder) since these are point-in-time values,
        // not running sums - each write fully replaces the previous one.
        final AtomicLong firstSpawnedNearbyMillis = new AtomicLong(0L);
        final AtomicLong lastSpawnedNearbyMillis = new AtomicLong(0L);
        final AtomicLong firstProvocationMillis = new AtomicLong(0L);
        final AtomicLong lastProvocationMillis = new AtomicLong(0L);
        final AtomicLong lastLocationPickMissMillis = new AtomicLong(0L);
    }

    /**
     * Optional write-through target for this tracker's counter updates,
     * implemented by PlanStatsRepository and wired in by PlanHook only when
     * Plan Player Analytics is installed and its Query API is available.
     *
     * Deliberately package-private and free of any Plan import itself
     * (UUID/long parameters only) - PhantomStatsTracker must stay loadable
     * with or without Plan present, so this interface can't reference any
     * Plan type without reintroducing the exact NoClassDefFoundError risk
     * PlanHook's isolation is meant to prevent. PlanStatsRepository is what
     * bridges the two: it implements this plain interface on one side and
     * holds the Plan QueryService on the other.
     *
     * Every method here is called synchronously from the same hot call
     * sites as the corresponding PhantomStatsTracker#record* method
     * (including Folia per-entity/per-region scheduler threads), so
     * implementations MUST return promptly and push their actual I/O onto
     * Plan's own async QueryService#execute rather than blocking here.
     */
    interface StatsPersistenceSink {
        /** Mirrors {@link #recordEndSpawnerSuccess}. {@code atMillis} is the exact instant already recorded in memory, kept identical rather than re-read to avoid any skew between the two. */
        void onEndSpawnerSuccess(UUID playerId, long atMillis);

        /** Mirrors {@link #recordEndSpawnerVetoed}. Server-wide only; no player is attributable at this call site (see PhantomEndSpawner). */
        void onEndSpawnerVetoed();

        /** Mirrors {@link #recordLocationPickMiss}. {@code atMillis} is the exact instant already recorded in memory. */
        void onLocationPickMiss(UUID playerId, long atMillis);

        /** Mirrors {@link #recordProvocation}. {@code atMillis} is the exact instant already recorded in memory. */
        void onProvocation(UUID playerId, long atMillis);
    }

    /**
     * Immutable snapshot of one player's persisted counters/timestamps, as
     * loaded from Plan's database by PlanStatsRepository and handed to
     * {@link #restorePlayerCounters} during startup hydration. Field order
     * matches PlayerCounters' declaration order for easy visual comparison.
     */
    record PersistedPlayerCounters(
            long phantomsSpawnedNearby,
            long provocations,
            long locationPickMisses,
            long activeNearby,
            long firstSpawnedNearbyMillis,
            long lastSpawnedNearbyMillis,
            long firstProvocationMillis,
            long lastProvocationMillis,
            long lastLocationPickMissMillis
    ) {
    }

    /**
     * Immutable snapshot of the server-wide totals, as loaded from Plan's
     * database by PlanStatsRepository and handed to
     * {@link #restoreServerTotals} during startup hydration.
     */
    record PersistedServerTotals(
            long totalEndSpawnerSpawns,
            long totalEndSpawnerVetoedSpawns,
            long totalProvocations,
            long totalLocationPickMisses,
            long totalLocationPickAttempts
    ) {
    }
}