package com.anarchyphantoms.phantomcontrol;

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.annotation.BooleanProvider;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.annotation.PercentageProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;

import java.util.UUID;

/**
 * Supplies AnarchyPhantoms' activity stats to Plan Player Analytics, so they
 * show up in Plan's "Plugins" overview (both the server-wide page and each
 * individual player's page) without needing Plan installed to run this
 * plugin at all - see PlanHook for how/when this actually gets registered.
 *
 * Deliberately excludes every class in com.djrapitops.plan.* from anywhere
 * else in the codebase (see PlanHook's class javadoc for why).
 *
 * All data here is read from PhantomStatsTracker, a plain in-memory
 * aggregator fed by the plugin's existing listeners (PhantomEndSpawner,
 * PhantomBehaviorListener) at the same points they already produce debug
 * output - this class does no Bukkit API calls of its own and holds no
 * state beyond a reference to that tracker.
 */
@PluginInfo(
        name = "AnarchyPhantoms",
        iconName = "wind",
        iconFamily = Family.SOLID,
        color = Color.LIGHT_BLUE
)
public final class AnarchyPhantomsDataExtension implements DataExtension {

    private final PhantomStatsTracker stats;

    AnarchyPhantomsDataExtension(PhantomStatsTracker stats) {
        this.stats = stats;
    }

    /**
     * Player-scoped methods are cheap counter reads (no I/O, no blocking),
     * so refreshing on join/leave/periodically all match what these numbers
     * are meant to reflect: "how has this player interacted with phantoms
     * over time". Server-scoped methods use the same set for the same
     * reason - PERIODICAL keeps the server-wide totals reasonably live
     * between restarts of Plan's own extension-registration cycle.
     */
    @Override
    public CallEvents[] callExtensionMethodsOn() {
        return new CallEvents[]{
                CallEvents.PLAYER_JOIN,
                CallEvents.PLAYER_LEAVE,
                CallEvents.PLAYER_PERIODICAL,
                CallEvents.SERVER_EXTENSION_REGISTER,
                CallEvents.SERVER_PERIODICAL
        };
    }

    // ---- Player-scoped stats (shown on each player's Plan page) ----

    @NumberProvider(
            text = "Phantoms Spawned Nearby",
            description = "Lifetime count of phantoms actively spawned near this player by AnarchyPhantoms' End-spawner",
            iconName = "cloud",
            iconColor = Color.LIGHT_BLUE,
            priority = 100,
            showInPlayerTable = true
    )
    public long phantomsSpawnedNearby(UUID playerUUID) {
        return stats.getPhantomsSpawnedNear(playerUUID);
    }

    @NumberProvider(
            text = "Phantoms Provoked",
            description = "How many phantoms this player has provoked (first hit only; repeat attacks on an already-hostile phantom don't count again)",
            iconName = "bolt",
            iconColor = Color.RED,
            priority = 90,
            showInPlayerTable = true
    )
    public long phantomsProvoked(UUID playerUUID) {
        return stats.getProvocationsBy(playerUUID);
    }

    @NumberProvider(
            text = "Phantoms Active Nearby",
            description = "Phantoms currently within AnarchyPhantoms' spawn-check radius of this player, as of the last check cycle",
            iconName = "circle-nodes",
            iconColor = Color.LIGHT_BLUE,
            priority = 80
    )
    public long phantomsActiveNearby(UUID playerUUID) {
        return stats.getActivePhantomsNear(playerUUID);
    }

    @BooleanProvider(
            text = "Has Provoked a Phantom",
            description = "Whether this player has ever provoked a phantom",
            iconName = "skull",
            iconColor = Color.RED,
            priority = 70
    )
    public boolean hasProvokedAPhantom(UUID playerUUID) {
        return stats.getProvocationsBy(playerUUID) > 0;
    }

    // ---- Server-scoped stats (shown on Plan's server overview page) ----

    @NumberProvider(
            text = "Total Phantoms Spawned",
            description = "Lifetime count of phantoms actively spawned in The End by AnarchyPhantoms, across all players",
            iconName = "cloud",
            iconColor = Color.LIGHT_BLUE,
            priority = 100
    )
    public long totalPhantomsSpawned() {
        return stats.getTotalEndSpawnerSpawns();
    }

    @NumberProvider(
            text = "Total Provocations",
            description = "Lifetime count of phantoms provoked (turned hostile) across the whole server",
            iconName = "bolt",
            iconColor = Color.RED,
            priority = 90
    )
    public long totalProvocations() {
        return stats.getTotalProvocations();
    }

    @PercentageProvider(
            text = "End-Spawner Success Rate",
            description = "Share of AnarchyPhantoms' active spawn attempts that resulted in a live phantom, rather than being vetoed (e.g. by the surface/dimension checks)",
            iconName = "chart-line",
            iconColor = Color.LIGHT_BLUE,
            priority = 80
    )
    public double endSpawnerSuccessRate() {
        return stats.getEndSpawnerSuccessRate();
    }

    // Group-scoped provider (@GroupProvider, Group parameter) intentionally
    // omitted: AnarchyPhantoms has no concept of player groupings (jobs,
    // towns, permission tiers, etc.) to report against - a @GroupProvider
    // only makes sense once such a grouping exists to hang it off. If the
    // plugin ever gains one (e.g. per-region "zones"), add a method here
    // taking a Group parameter rather than introducing a new provider type.
}