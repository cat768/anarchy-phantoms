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

    private final AnarchyPhantomsPlugin plugin;
    private final PhantomProvocationTracker tracker;

    public PhantomBehaviorListener(AnarchyPhantomsPlugin plugin, PhantomProvocationTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
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

        tracker.markProvoked(phantom, phantom.getWorld().getFullTime());
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
