package com.anarchyphantoms.phantomcontrol;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Phantom;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Lets {@link PhantomEndSpawner} attribute a spawn to the specific player who
 * caused it, so {@link PhantomSpawnListener} - which is the single place
 * that actually emits the "phantom spawned" debug report, since it's the
 * last thing to run before a spawn is finalized or vetoed - can read that
 * attribution back out instead of either:
 *
 *  - duplicating the report (both classes independently logging the same
 *    spawn), or
 *  - PhantomSpawnListener falling back to the generic "spawn reason NATURAL"
 *    label for End-spawner spawns, which is technically true (see the
 *    SpawnReason.NATURAL comment in PhantomEndSpawner) but useless for
 *    debugging - it can't distinguish "vanilla-style natural spawn" from
 *    "this plugin's own active End spawner", which was the entire point of
 *    the "caused by which player" requirement.
 *
 * The tag is written to the entity's PersistentDataContainer synchronously,
 * in the brief window between World#spawn(...) starting (which is what
 * fires CreatureSpawnEvent) and PhantomSpawnListener's own handler running -
 * both happen on the same thread/tick, so there's no race. The tag is
 * deliberately left on the entity afterward (not cleaned up): it's cheap,
 * useful to retain for later inspection (e.g. via NBT tools), and nothing
 * else reads it as authoritative state the way provocation status is read.
 */
final class PhantomSpawnCauseTag {

    private final NamespacedKey causeKey;

    PhantomSpawnCauseTag(AnarchyPhantomsPlugin plugin) {
        this.causeKey = new NamespacedKey(plugin, "spawn_cause");
    }

    /** Tags this phantom with a human-readable cause label, e.g. "End-spawner near player Notch". */
    void tag(Phantom phantom, String causeLabel) {
        phantom.getPersistentDataContainer().set(causeKey, PersistentDataType.STRING, causeLabel);
    }

    /** Reads back a previously-set cause label, or null if this phantom was never tagged. */
    String read(Phantom phantom) {
        PersistentDataContainer data = phantom.getPersistentDataContainer();
        return data.get(causeKey, PersistentDataType.STRING);
    }
}