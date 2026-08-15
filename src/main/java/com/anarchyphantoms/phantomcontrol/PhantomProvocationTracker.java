package com.anarchyphantoms.phantomcontrol;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Phantom;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Tracks, per individual phantom entity, whether it has been provoked
 * (attacked by a player). Provoked state is stored directly on the entity
 * via PersistentDataContainer so it survives chunk unload/reload without
 * needing an in-memory map (and without leaking memory).
 */
public final class PhantomProvocationTracker {

    private final NamespacedKey provokedKey;
    private final NamespacedKey provokedAtKey;

    public PhantomProvocationTracker(AnarchyPhantomsPlugin plugin) {
        this.provokedKey = new NamespacedKey(plugin, "provoked");
        this.provokedAtKey = new NamespacedKey(plugin, "provoked_at_tick");
    }

    /**
     * Marks the phantom as provoked (hostile + vocal) as of the given world tick.
     */
    public void markProvoked(Phantom phantom, long currentTick) {
        PersistentDataContainer data = phantom.getPersistentDataContainer();
        data.set(provokedKey, PersistentDataType.BYTE, (byte) 1);
        data.set(provokedAtKey, PersistentDataType.LONG, currentTick);
    }

    public void clearProvoked(Phantom phantom) {
        PersistentDataContainer data = phantom.getPersistentDataContainer();
        data.remove(provokedKey);
        data.remove(provokedAtKey);
    }

    public boolean isProvoked(Phantom phantom) {
        PersistentDataContainer data = phantom.getPersistentDataContainer();
        Byte value = data.get(provokedKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    /**
     * Returns the world tick the phantom was provoked at, or -1 if it has never been provoked.
     */
    public long getProvokedAtTick(Phantom phantom) {
        PersistentDataContainer data = phantom.getPersistentDataContainer();
        Long value = data.get(provokedAtKey, PersistentDataType.LONG);
        return value != null ? value : -1L;
    }
}
