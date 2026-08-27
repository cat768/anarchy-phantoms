package com.anarchyphantoms.phantomcontrol;

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.annotation.BooleanProvider;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

/**
 * Plan DataExtension for AnarchyPhantoms.
 *
 * The {@link PluginInfo} annotation below is what makes "AnarchyPhantoms"
 * show up as its own entry in Plan's "Plugins" overview (both the server
 * page and, for the player methods, each player's page) - it does not need
 * any registration beyond {@link PlanHook#hookIntoPlan()} calling
 * {@code ExtensionService.register(new AnarchyPhantomsDataExtension(...))}.
 *
 * All data here is read from {@link PhantomPlayerStats}, which is backed by
 * per-player PersistentDataContainer entries (see that class) rather than a
 * separate database, so there is nothing extra to migrate or keep in sync.
 *
 * @see <a href="https://github.com/plan-player-analytics/Plan/wiki/APIv5---DataExtension-API">
 *      Plan's DataExtension API reference</a>
 */
@PluginInfo(
        name = "AnarchyPhantoms",
        iconName = "wind",
        iconFamily = Family.SOLID,
        color = Color.LIGHT_BLUE
)
public final class AnarchyPhantomsDataExtension implements DataExtension {

    private final PhantomPlayerStats playerStats;

    public AnarchyPhantomsDataExtension(PhantomPlayerStats playerStats) {
        this.playerStats = playerStats;
    }

    /**
     * Determines when Plan (re)calls the provider methods below for a given
     * player. PLAYER_JOIN and PLAYER_LEAVE cover the normal session
     * lifecycle; since the underlying counters only ever change while a
     * player is online (a phantom can only be provoked/killed by someone
     * currently in the world), those two events are sufficient - there's no
     * need for a periodic re-poll of an offline player's unchanging count.
     */
    @Override
    public CallEvents[] callExtensionMethodsOn() {
        return new CallEvents[]{
                CallEvents.PLAYER_JOIN,
                CallEvents.PLAYER_LEAVE
        };
    }

    @NumberProvider(
            text = "Phantoms Provoked",
            description = "How many phantoms this player has attacked and turned hostile",
            iconName = "bolt",
            iconColor = Color.YELLOW,
            priority = 100,
            showInPlayerTable = true
    )
    public long phantomsProvoked(UUID playerUUID) {
        return playerStats.getProvokedCount(resolvePlayer(playerUUID));
    }

    @NumberProvider(
            text = "Phantoms Killed",
            description = "How many phantoms this player has killed",
            iconName = "skull",
            iconColor = Color.RED,
            priority = 90,
            showInPlayerTable = true
    )
    public long phantomsKilled(UUID playerUUID) {
        return playerStats.getKilledCount(resolvePlayer(playerUUID));
    }

    @BooleanProvider(
            text = "Has Provoked a Phantom",
            description = "Whether this player has ever turned a phantom hostile",
            iconName = "triangle-exclamation",
            iconColor = Color.ORANGE,
            priority = 80
    )
    public boolean hasProvokedPhantom(UUID playerUUID) {
        return playerStats.getProvokedCount(resolvePlayer(playerUUID)) > 0;
    }

    /**
     * Plan's provider methods are handed a UUID rather than a live Player,
     * since they can be called for offline players too (e.g. re-rendering a
     * cached page). Bukkit.getOfflinePlayer(UUID) resolves either way, and
     * PhantomPlayerStats is written against OfflinePlayer for exactly this
     * reason - see its class javadoc.
     */
    private OfflinePlayer resolvePlayer(UUID playerUUID) {
        return Bukkit.getOfflinePlayer(playerUUID);
    }
}