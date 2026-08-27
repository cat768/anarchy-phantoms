package com.anarchyphantoms.phantomcontrol;

import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

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

    /**
     * Called by PhantomEndSpawner right after a successful active-spawn
     * (the phantom passed PhantomSpawnListener's veto check and is alive in
     * the world), attributing it to the player whose presence triggered it.
     */
    public void recordEndSpawnerSuccess(Player nearPlayer) {
        totalEndSpawnerSpawns.increment();
        counters(nearPlayer.getUniqueId()).phantomsSpawnedNearby.increment();
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
     * Called by PhantomBehaviorListener exactly when a phantom transitions
     * from not-provoked to provoked (i.e. tracker.markProvoked(...) just
     * returned true) - one call per genuinely new provocation, not per hit
     * on an already-hostile phantom.
     */
    public void recordProvocation(Player attacker) {
        totalProvocations.increment();
        counters(attacker.getUniqueId()).provocations.increment();
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
        final AtomicLong activeNearby = new AtomicLong(0L);
    }
}