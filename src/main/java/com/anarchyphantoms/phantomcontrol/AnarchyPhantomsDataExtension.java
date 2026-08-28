package com.anarchyphantoms.phantomcontrol;

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.annotation.BooleanProvider;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.annotation.PercentageProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.annotation.StringProvider;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    /**
     * Formatter for the human-readable @StringProvider timestamp methods
     * below (e.g. "First/Last Phantom Nearby"). Plan's own player-page UI
     * already timestamps events it knows about natively, but these values
     * come from this plugin's own tracker, not Plan's session data, so they
     * need to be formatted here rather than relying on Plan to do it.
     * UTC is used rather than the server's local zone since Plan's web UI
     * is typically viewed by admins in arbitrary time zones and Plan itself
     * generally reports in UTC/browser-local rather than server-local time -
     * matching that convention avoids a display that silently disagrees
     * with the rest of the page.
     */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("UTC"));

    private final PhantomStatsTracker stats;

    AnarchyPhantomsDataExtension(PhantomStatsTracker stats) {
        this.stats = stats;
    }

    /** Formats an epoch-millis timestamp for display, or "Never" if it's the tracker's 0L "no data yet" sentinel. */
    private static String formatTimestamp(long epochMillis) {
        if (epochMillis <= 0L) {
            return "Never";
        }
        return TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis)) + " UTC";
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

    @NumberProvider(
            text = "End-Spawn Location Misses",
            description = "Times AnarchyPhantoms rolled to spawn a phantom near this player but found no allowed surface block (e.g. end stone/chorus) in any sampled column nearby - common on thin bridges or void-heavy outer End islands",
            iconName = "triangle-exclamation",
            iconColor = Color.YELLOW,
            priority = 65,
            showInPlayerTable = true
    )
    public long endSpawnLocationMisses(UUID playerUUID) {
        return stats.getLocationPickMissesFor(playerUUID);
    }

    @StringProvider(
            text = "First Phantom Nearby",
            description = "When AnarchyPhantoms first actively spawned a phantom near this player",
            iconName = "hourglass-start",
            iconColor = Color.LIGHT_BLUE,
            priority = 60
    )
    public String firstPhantomNearby(UUID playerUUID) {
        return formatTimestamp(stats.getFirstSpawnedNearbyMillis(playerUUID));
    }

    @StringProvider(
            text = "Last Phantom Nearby",
            description = "When AnarchyPhantoms most recently actively spawned a phantom near this player",
            iconName = "hourglass-end",
            iconColor = Color.LIGHT_BLUE,
            priority = 50
    )
    public String lastPhantomNearby(UUID playerUUID) {
        return formatTimestamp(stats.getLastSpawnedNearbyMillis(playerUUID));
    }

    @StringProvider(
            text = "First Provocation",
            description = "When this player first provoked a phantom",
            iconName = "hourglass-start",
            iconColor = Color.RED,
            priority = 40
    )
    public String firstProvocation(UUID playerUUID) {
        return formatTimestamp(stats.getFirstProvocationMillis(playerUUID));
    }

    @StringProvider(
            text = "Last Provocation",
            description = "When this player most recently provoked a phantom",
            iconName = "hourglass-end",
            iconColor = Color.RED,
            priority = 30
    )
    public String lastProvocation(UUID playerUUID) {
        return formatTimestamp(stats.getLastProvocationMillis(playerUUID));
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
            description = "Share of AnarchyPhantoms' active spawn attempts that resulted in a live phantom, rather than being vetoed after world.spawn() (e.g. by the surface/dimension checks)",
            iconName = "chart-line",
            iconColor = Color.LIGHT_BLUE,
            priority = 80
    )
    public double endSpawnerSuccessRate() {
        return stats.getEndSpawnerSuccessRate();
    }

    @NumberProvider(
            text = "Total Location Pick Misses",
            description = "Server-wide count of pickSpawnLocation exhausting every sampled column with no allowed surface block found - i.e. attempts where the End-spawner never even reached world.spawn(), distinct from vetoed spawns above",
            iconName = "triangle-exclamation",
            iconColor = Color.YELLOW,
            priority = 75
    )
    public long totalLocationPickMisses() {
        return stats.getTotalLocationPickMisses();
    }

    @PercentageProvider(
            text = "Location Pick Miss Rate",
            description = "Share of all End-spawner attempts (successful spawns + vetoed spawns + location-pick misses) that missed before ever reaching world.spawn(). A high/rising rate usually indicates allowed-surface-blocks or the spawn columns' horizontal spread need tuning for this map's terrain (thin bridges, void-heavy outer islands), rather than an issue with the surface/dimension veto checks themselves",
            iconName = "chart-line",
            iconColor = Color.YELLOW,
            priority = 70
    )
    public double locationPickMissRate() {
        return stats.getLocationPickMissRate();
    }

    @NumberProvider(
            text = "Total Vetoed Spawns",
            description = "Server-wide count of End-spawner attempts that reached world.spawn() but were rejected afterward (e.g. by PhantomSpawnListener's surface/dimension checks, or another plugin)",
            iconName = "ban",
            iconColor = Color.RED,
            priority = 65
    )
    public long totalVetoedSpawns() {
        return stats.getTotalEndSpawnerVetoedSpawns();
    }

    // Group-scoped provider (@GroupProvider, Group parameter) intentionally
    // omitted: AnarchyPhantoms has no concept of player groupings (jobs,
    // towns, permission tiers, etc.) to report against - a @GroupProvider
    // only makes sense once such a grouping exists to hang it off. If the
    // plugin ever gains one (e.g. per-region "zones"), add a method here
    // taking a Group parameter rather than introducing a new provider type.
}