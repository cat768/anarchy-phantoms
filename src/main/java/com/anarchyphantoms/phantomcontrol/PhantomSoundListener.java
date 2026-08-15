package com.anarchyphantoms.phantomcontrol;

import org.bukkit.entity.Phantom;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Suppresses the phantom's ambient "screech" sound (and other vocalizations)
 * until that specific phantom has been provoked (attacked by a player).
 *
 * There is no cancellable "entity is about to make a sound" event in the
 * Bukkit/Paper API (an earlier version of this class referenced a
 * io.papermc.paper.event.entity.EntitySoundEvent that does not actually
 * exist in any Paper version). Ambient mob sounds are played server-side
 * from the entity's internal AI/sound logic and are not routed through a
 * Bukkit event at all.
 *
 * Instead, this silences the sound at the source using the standard
 * LivingEntity#setSilent(boolean) API:
 *  - Newly spawned phantoms are silenced immediately.
 *  - A phantom is un-silenced the moment it's marked provoked (on damage).
 *  - Because provocation can be time-limited (see
 *    PluginSettings#getProvokedDurationTicks / PhantomBehaviorListener),
 *    a lightweight repeating task periodically re-silences phantoms whose
 *    provocation window has expired.
 */
public final class PhantomSoundListener implements Listener {

    private static final long RECHECK_INTERVAL_TICKS = 20L; // once per second

    private final AnarchyPhantomsPlugin plugin;
    private final PhantomProvocationTracker tracker;

    private BukkitTask recheckTask;

    public PhantomSoundListener(AnarchyPhantomsPlugin plugin, PhantomProvocationTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    /**
     * Registers this listener's periodic re-check task. Call once from the
     * plugin's onEnable, after the listener itself has been registered with
     * the PluginManager.
     */
    public void startRecheckTask() {
        if (recheckTask != null) {
            return;
        }
        recheckTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::recheckAllPhantoms,
                RECHECK_INTERVAL_TICKS,
                RECHECK_INTERVAL_TICKS
        );
    }

    /**
     * Cancels the periodic re-check task. Call from the plugin's onDisable.
     */
    public void stopRecheckTask() {
        if (recheckTask != null) {
            recheckTask.cancel();
            recheckTask = null;
        }
    }

    /**
     * Freshly spawned phantoms start silent, matching the passive-until-attacked
     * behavior in PhantomBehaviorListener.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPhantomSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof Phantom phantom)) {
            return;
        }
        if (!plugin.getSettings().isSilenceScreechUntilAttacked()) {
            return;
        }
        phantom.setSilent(true);
    }

    /**
     * The moment a phantom is provoked (same trigger PhantomBehaviorListener
     * uses to mark it hostile), un-silence it so its screech plays normally.
     * Runs at MONITOR priority, after PhantomBehaviorListener has already
     * called tracker.markProvoked(...) for this event.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPhantomDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Phantom phantom)) {
            return;
        }
        if (!plugin.getSettings().isSilenceScreechUntilAttacked()) {
            return;
        }
        if (tracker.isProvoked(phantom)) {
            phantom.setSilent(false);
        }
    }

    /**
     * Periodically re-syncs silent state for all loaded phantoms, in case a
     * time-limited provocation window has expired (nothing fires an event
     * when time simply passes, so this has to be polled).
     */
    private void recheckAllPhantoms() {
        if (!plugin.getSettings().isSilenceScreechUntilAttacked()) {
            return;
        }
        plugin.getServer().getWorlds().forEach(world ->
                world.getEntitiesByClass(Phantom.class).forEach(this::syncSilentState)
        );
    }

    private void syncSilentState(Phantom phantom) {
        boolean shouldBeSilent = !tracker.isProvoked(phantom);
        if (phantom.isSilent() != shouldBeSilent) {
            phantom.setSilent(shouldBeSilent);
        }
    }
}
