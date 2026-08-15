package com.anarchywithers.phantomcontrol;

import io.papermc.paper.event.entity.EntitySoundEvent;
import org.bukkit.Sound;
import org.bukkit.entity.Phantom;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Suppresses the phantom's ambient "screech" sound (and other vocalizations)
 * until that specific phantom has been provoked (attacked by a player).
 * Uses Paper's EntitySoundEvent, which fires for entity-emitted sounds
 * (ambient, hurt, death, etc.) and is cancellable.
 */
public final class PhantomSoundListener implements Listener {

    private final AnarchyWithersPlugin plugin;
    private final PhantomProvocationTracker tracker;

    public PhantomSoundListener(AnarchyWithersPlugin plugin, PhantomProvocationTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntitySound(EntitySoundEvent event) {
        if (!(event.getEntity() instanceof Phantom phantom)) {
            return;
        }
        if (!plugin.getSettings().isSilenceScreechUntilAttacked()) {
            return;
        }

        Sound sound = event.getSound();
        if (!isPhantomVocalSound(sound)) {
            return;
        }

        if (!tracker.isProvoked(phantom)) {
            event.setCancelled(true);
        }
    }

    /**
     * Identifies the phantom's ambient screech and swoop sounds. Hurt/death
     * sounds are intentionally left alone (a hit phantom is, by definition,
     * already provoked by the time it takes damage-caused sound events fire,
     * but we still only match ambient/flap sounds here to be safe and explicit).
     */
    private boolean isPhantomVocalSound(Sound sound) {
        String key = sound.getKey().getKey();
        return key.equals("entity.phantom.ambient")
                || key.equals("entity.phantom.flap")
                || key.equals("entity.phantom.swoop");
    }
}
