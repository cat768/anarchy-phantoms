package com.anarchyphantoms.phantomcontrol;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Phantom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link PhantomProvocationTracker} in isolation from the
 * event-handling listeners. This is pure PDC-backed state logic (no Bukkit
 * events involved at all), so it's tested directly against a real mock
 * Phantom entity rather than through event firing - the fastest and most
 * precise layer to catch a regression in the provoked/provoked-at-tick
 * bookkeeping itself.
 */
class PhantomProvocationTrackerTest extends PluginTestBase {

    private Phantom spawnPhantom() {
        setFloor(endWorld, 63, Material.END_STONE);
        Location loc = new Location(endWorld, 0.5, 64, 0.5);
        return endWorld.spawn(loc, Phantom.class);
    }

    @Test
    void freshPhantomIsNotProvoked() {
        Phantom phantom = spawnPhantom();
        PhantomProvocationTracker tracker = new PhantomProvocationTracker(plugin);

        assertFalse(tracker.isProvoked(phantom), "A freshly spawned phantom must not start provoked");
        assertEquals(-1L, tracker.getProvokedAtTick(phantom), "An unprovoked phantom has no provoked-at tick");
    }

    @Test
    void markProvokedTransitionsFromNotProvokedToProvoked() {
        Phantom phantom = spawnPhantom();
        PhantomProvocationTracker tracker = new PhantomProvocationTracker(plugin);

        boolean newlyProvoked = tracker.markProvoked(phantom, 100L);

        assertTrue(newlyProvoked, "Provoking a not-yet-provoked phantom must report a genuinely new provocation");
        assertTrue(tracker.isProvoked(phantom));
        assertEquals(100L, tracker.getProvokedAtTick(phantom));
    }

    @Test
    void reProvokingAnAlreadyProvokedPhantomIsNotReportedAsNew() {
        // Callers (PhantomBehaviorListener) rely on this return value to
        // decide whether to fire a "became aggressive" notification/stat -
        // a repeat hit on an already-hostile phantom must NOT re-fire that.
        Phantom phantom = spawnPhantom();
        PhantomProvocationTracker tracker = new PhantomProvocationTracker(plugin);

        tracker.markProvoked(phantom, 100L);
        boolean secondCallIsNew = tracker.markProvoked(phantom, 150L);

        assertFalse(secondCallIsNew, "Marking an already-provoked phantom again must not report as newly provoked");
        // The provoked-at tick should still update to the latest hit, even
        // though it's not treated as a "new" provocation.
        assertEquals(150L, tracker.getProvokedAtTick(phantom));
    }

    @Test
    void clearProvokedResetsState() {
        Phantom phantom = spawnPhantom();
        PhantomProvocationTracker tracker = new PhantomProvocationTracker(plugin);
        tracker.markProvoked(phantom, 100L);

        tracker.clearProvoked(phantom);

        assertFalse(tracker.isProvoked(phantom));
        assertEquals(-1L, tracker.getProvokedAtTick(phantom));
    }

    @Test
    void provocationStateIsPerEntityNotGlobal() {
        Phantom phantomA = spawnPhantom();
        Phantom phantomB = spawnPhantom();
        PhantomProvocationTracker tracker = new PhantomProvocationTracker(plugin);

        tracker.markProvoked(phantomA, 100L);

        assertTrue(tracker.isProvoked(phantomA));
        assertFalse(tracker.isProvoked(phantomB), "Provoking one phantom must not affect another phantom's state");
    }
}
