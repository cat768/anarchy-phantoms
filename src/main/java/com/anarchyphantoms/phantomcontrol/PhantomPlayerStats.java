package com.anarchyphantoms.phantomcontrol;

import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Tracks lightweight, per-player phantom-related counters: how many
 * phantoms a player has provoked (attacked first) and how many they've
 * killed. Stored directly on the player's own PersistentDataContainer,
 * same pattern as {@link PhantomProvocationTracker} - no database, no
 * in-memory map to leak, and it survives restarts for free since Bukkit
 * persists player PDC to disk between sessions.
 *
 * This class only counts; it has no opinion on when a provocation or kill
 * "counts" - that judgment stays in the listeners that already own that
 * logic (PhantomBehaviorListener owns both the provocation and death
 * handling), keeping this a dumb, reusable counter rather than a second
 * place game rules could drift out of sync.
 *
 * Every method is typed on {@link OfflinePlayer} (which {@link Player}
 * extends) rather than {@link Player}, because Plan's DataExtension
 * provider methods (see {@link AnarchyPhantomsDataExtension}) are handed a
 * UUID that may belong to a currently-offline player when Plan re-renders a
 * cached page - Bukkit.getOfflinePlayer(UUID) resolves either way, and
 * OfflinePlayer#getPersistentDataContainer() works the same regardless of
 * online status on Paper. The plugin's own listeners, which only ever act
 * on a live Player, pass one in like any other OfflinePlayer.
 *
 * These counters exist primarily to give the Plan DataExtension real
 * per-player numbers to display; nothing in the plugin's core spawn/behavior
 * logic depends on them.
 */
public final class PhantomPlayerStats {

    private final NamespacedKey provokedCountKey;
    private final NamespacedKey killedCountKey;

    public PhantomPlayerStats(AnarchyPhantomsPlugin plugin) {
        this.provokedCountKey = new NamespacedKey(plugin, "phantoms_provoked_count");
        this.killedCountKey = new NamespacedKey(plugin, "phantoms_killed_count");
    }

    /** Increments and returns this player's lifetime "phantoms provoked" count. */
    public long incrementProvoked(OfflinePlayer player) {
        return increment(player, provokedCountKey);
    }

    /** Increments and returns this player's lifetime "phantoms killed" count. */
    public long incrementKilled(OfflinePlayer player) {
        return increment(player, killedCountKey);
    }

    public long getProvokedCount(OfflinePlayer player) {
        return get(player, provokedCountKey);
    }

    public long getKilledCount(OfflinePlayer player) {
        return get(player, killedCountKey);
    }

    private long increment(OfflinePlayer player, NamespacedKey key) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        long next = get(player, key) + 1L;
        data.set(key, PersistentDataType.LONG, next);
        return next;
    }

    private long get(OfflinePlayer player, NamespacedKey key) {
        Long value = player.getPersistentDataContainer().get(key, PersistentDataType.LONG);
        return value != null ? value : 0L;
    }
}