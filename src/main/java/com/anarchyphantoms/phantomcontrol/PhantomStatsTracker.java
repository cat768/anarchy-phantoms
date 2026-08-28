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
 * Deliberately in-memory rather than persisted:
 *  - PhantomProvocationTracker already persists per-entity state via PDC,
 *    which is the state that actually needs to survive a restart (governs
 *    behavior). These counters are display-only aggregates derived from
 *    events as they happen; losing them on restart just means Plan (or
 *    anything else reading this class) reports "since last restart" numbers,
 *    which is an acceptable, common pattern for this kind of live counter.
 *  - Avoids adding a database/file dependency to a plugin that currently has
 *    none, purely to support an optional third-party integration.
 *
 * Thread-safety: counters use LongAdder/AtomicLong and the per-player map is
 * a ConcurrentHashMap, since call sites span multiple per-player/per-entity
 * EntityScheduler threads under Folia (see PhantomEndSpawner, PhantomBehaviorListener)
 * as well as Plan's own async extension-method calls. No method here does
 * any Bukkit API access (no entity/world reads), so none of it needs to run
 * on any particular region thread - it's plain counter bookkeeping.
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
     * Called by PhantomEndSpawner right after a successful active-spawn
     * (the phantom passed PhantomSpawnListener's veto check and is alive in
     * the world), attributing it to the player whose presence triggered it.
     */
    public void recordEndSpawnerSuccess(Player nearPlayer) {
        totalEndSpawnerSpawns.increment();
        totalLocationPickAttempts.increment();
        PlayerCounters c = counters(nearPlayer.getUniqueId());
        c.phantomsSpawnedNearby.increment();
        long now = System.currentTimeMillis();
        c.lastSpawnedNearbyMillis.set(now);
        c.firstSpawnedNearbyMillis.compareAndSet(0L, now);
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
        PlayerCounters c = counters(player.getUniqueId());
        c.locationPickMisses.increment();
        c.lastLocationPickMissMillis.set(System.currentTimeMillis());

        LOGGER.log(Level.INFO, "[AnarchyPhantoms] End-spawn location pick missed for {0} "
                        + "(no allowed surface block found in any sampled column near {1}, {2}, {3})",
                new Object[]{
                        player.getName(),
                        player.getLocation().getBlockX(),
                        player.getLocation().getBlockY(),
                        player.getLocation().getBlockZ()
                });
    }

    /**
     * Called by PhantomBehaviorListener exactly when a phantom transitions
     * from not-provoked to provoked (i.e. tracker.markProvoked(...) just
     * returned true) - one call per genuinely new provocation, not per hit
     * on an already-hostile phantom.
     */
    public void recordProvocation(Player attacker) {
        totalProvocations.increment();
        PlayerCounters c = counters(attacker.getUniqueId());
        c.provocations.increment();
        long now = System.currentTimeMillis();
        c.lastProvocationMillis.set(now);
        c.firstProvocationMillis.compareAndSet(0L, now);
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
}