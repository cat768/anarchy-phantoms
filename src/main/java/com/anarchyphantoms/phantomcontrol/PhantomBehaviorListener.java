package com.anarchyphantoms.phantomcontrol;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

/**
 * Keeps phantoms passive (non-targeting) until a player actually attacks one.
 * Once attacked, that specific phantom becomes hostile (its normal AI target
 * behavior is allowed to proceed) for the configured provocation window.
 */
public final class PhantomBehaviorListener implements Listener {

    // How often each provoked phantom rechecks whether its provocation
    // window has expired, purely to fire the "reverted to passive" debug
    // notification at roughly the moment it actually happens rather than
    // only discovering it lazily next time something calls
    // isCurrentlyProvoked(). Same per-entity EntityScheduler pattern as
    // PhantomSoundListener's silence recheck, for the same Folia-safety
    // reasons documented there.
    private static final long EXPIRY_RECHECK_INTERVAL_TICKS = 20L; // once per second

    private final AnarchyPhantomsPlugin plugin;
    private final PhantomProvocationTracker tracker;
    private final PhantomDebugNotifier debugNotifier;
    private final PhantomStatsTracker statsTracker;

    public PhantomBehaviorListener(AnarchyPhantomsPlugin plugin, PhantomProvocationTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.debugNotifier = plugin.getDebugNotifier();
        this.statsTracker = plugin.getStatsTracker();
    }

    /**
     * Newly spawned phantoms start passive: no AI target, no burning-in-daylight
     * quirks beyond vanilla, just no aggression. We don't alter pathfinding/flight,
     * only targeting, so they still fly around and behave visually like phantoms.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPhantomSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof Phantom phantom)) {
            return;
        }
        if (!plugin.getSettings().isPassiveUntilAttacked()) {
            return;
        }
        // Ensure freshly spawned phantoms have no lingering target and are
        // marked not-provoked (PDC is empty by default on a new entity, so
        // this is mostly defensive in case of plugin reload mid-life).
        phantom.setTarget(null);

        // Only relevant if provocation can actually expire; a permanent
        // provocation window (-1) never needs an expiry recheck.
        if (plugin.getSettings().getProvokedDurationTicks() >= 0) {
            startExpiryRecheckTask(phantom);
        }
    }

    /**
     * Schedules this phantom's own periodic provocation-expiry check via its
     * EntityScheduler, so a time-limited provocation window lapsing (which
     * fires no Bukkit event on its own) still produces a timely
     * "reverted to passive" debug line instead of only being noticed the
     * next time the phantom happens to try targeting something.
     */
    private void startExpiryRecheckTask(Phantom phantom) {
        phantom.getScheduler().runAtFixedRate(
                plugin,
                task -> {
                    if (!plugin.getSettings().isPassiveUntilAttacked()) {
                        task.cancel();
                        return;
                    }
                    if (!tracker.isProvoked(phantom)) {
                        // Never provoked yet, or already handled below and
                        // cleared - either way nothing to expire right now.
                        return;
                    }
                    if (isCurrentlyProvoked(phantom)) {
                        return; // still within the provocation window
                    }
                    // Window just lapsed: clear tracked state and report the
                    // passive/silent transition once, then stop rechecking
                    // this specific phantom - it only re-arms via a fresh
                    // onPhantomDamaged provocation, which starts a new task.
                    tracker.clearProvoked(phantom);
                    debugNotifier.becamePassive(phantom.getLocation(), "provocation window expired");
                    task.cancel();
                },
                null, // retired callback: phantom already gone, nothing to clean up
                EXPIRY_RECHECK_INTERVAL_TICKS,
                EXPIRY_RECHECK_INTERVAL_TICKS
        );
    }

    /**
     * Prevents an un-provoked phantom from acquiring a target at all. This is
     * what actually keeps it from being hostile: even though vanilla AI tries
     * to target nearby players, we veto that target selection until the
     * phantom has been attacked.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhantomTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Phantom phantom)) {
            return;
        }
        if (!plugin.getSettings().isPassiveUntilAttacked()) {
            return;
        }

        if (!isCurrentlyProvoked(phantom)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhantomTargetGeneric(EntityTargetEvent event) {
        // Catches non-living target reasons too (e.g. targeting a block or other
        // entity type in future versions); phantoms should only ever target
        // living entities, but we guard defensively.
        if (event instanceof EntityTargetLivingEntityEvent) {
            return; // already handled above with more specific event
        }
        if (!(event.getEntity() instanceof Phantom phantom)) {
            return;
        }
        if (!plugin.getSettings().isPassiveUntilAttacked()) {
            return;
        }
        if (!isCurrentlyProvoked(phantom)) {
            event.setCancelled(true);
        }
    }

    /**
     * When a phantom takes damage from a player (directly, or via a projectile
     * fired by a player), mark it provoked so it becomes hostile and vocal.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPhantomDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Phantom phantom)) {
            return;
        }

        Player attacker = resolvePlayerAttacker(event.getDamager());
        if (attacker == null) {
            return;
        }

        boolean newlyProvoked = tracker.markProvoked(phantom, phantom.getWorld().getFullTime());
        if (newlyProvoked) {
            // Force immediate re-acquisition of the attacker as a target.
            // Without this, the phantom is correctly marked provoked and
            // future EntityTargetLivingEntityEvents will be allowed through,
            // but vanilla AI doesn't necessarily retry target acquisition
            // right away (it already failed once while the phantom was
            // passive, and phantom target goals back off for a bit after a
            // failed attempt). That left provoked phantoms sitting idle for
            // several seconds after being hit instead of attacking, which is
            // the actual bug this line fixes.
            phantom.setTarget(attacker);
            debugNotifier.becameAggressive(phantom.getLocation(), "attacked by player " + attacker.getName());
            statsTracker.recordProvocation(attacker);
            // (Re-)arm the expiry recheck now that this phantom has a fresh
            // provocation window to eventually fall out of.
            if (plugin.getSettings().getProvokedDurationTicks() >= 0) {
                startExpiryRecheckTask(phantom);
            }
        }
    }

    /**
     * Resolves the actual attacking player from a damage source, unwrapping
     * projectiles (arrows, tridents, etc.) to find the shooter if it was a player.
     */
    private Player resolvePlayerAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    /**
     * A phantom is "currently" provoked if it has been marked provoked and,
     * when a finite provocation window is configured, that window has not
     * yet elapsed.
     */
    private boolean isCurrentlyProvoked(Phantom phantom) {
        if (!tracker.isProvoked(phantom)) {
            return false;
        }

        long provokedDuration = plugin.getSettings().getProvokedDurationTicks();
        if (provokedDuration < 0) {
            return true; // permanent provocation once attacked
        }

        long provokedAt = tracker.getProvokedAtTick(phantom);
        if (provokedAt < 0) {
            return false;
        }

        long now = phantom.getWorld().getFullTime();
        return (now - provokedAt) <= provokedDuration;
    }
}