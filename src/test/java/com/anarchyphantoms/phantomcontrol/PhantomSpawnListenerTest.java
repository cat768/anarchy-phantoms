package com.anarchyphantoms.phantomcontrol;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Phantom;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral coverage for {@link PhantomSpawnListener}: this is the class
 * that actually enforces "End-only, above endstone/chorus" - the rule the
 * whole plugin exists to apply. Prior to this test suite, CI only proved
 * the plugin didn't crash on boot; NONE of these rules were ever asserted
 * against a real CreatureSpawnEvent.
 *
 * Every test here fires a real {@link CreatureSpawnEvent} through the
 * plugin's actual registered listener (via the mock PluginManager) and
 * asserts on event.isCancelled() - the exact mechanism the real class uses
 * to veto a spawn - rather than re-implementing the surface-check logic in
 * the test itself.
 */
class PhantomSpawnListenerTest extends PluginTestBase {

    private CreatureSpawnEvent fireSpawnEvent(Location location, CreatureSpawnEvent.SpawnReason reason) {
        Phantom phantom = location.getWorld().spawn(location, Phantom.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(phantom, reason);
        server.getPluginManager().callEvent(event);
        return event;
    }

    @Test
    void naturalSpawnInOverworldIsCancelled() {
        setFloor(overworld, 63, Material.GRASS_BLOCK);
        Location loc = new Location(overworld, 0.5, 64, 0.5);

        CreatureSpawnEvent event = fireSpawnEvent(loc, CreatureSpawnEvent.SpawnReason.NATURAL);

        assertTrue(event.isCancelled(), "A natural phantom spawn in the overworld must be vetoed");
    }

    @Test
    void naturalSpawnInEndAboveEndStoneIsAllowed() {
        setFloor(endWorld, 63, Material.END_STONE);
        Location loc = new Location(endWorld, 0.5, 64, 0.5);

        CreatureSpawnEvent event = fireSpawnEvent(loc, CreatureSpawnEvent.SpawnReason.NATURAL);

        assertFalse(event.isCancelled(), "A natural phantom spawn in The End above end stone must be allowed");
    }

    @Test
    void naturalSpawnInEndAboveDisallowedSurfaceIsCancelled() {
        setFloor(endWorld, 63, Material.OBSIDIAN);
        Location loc = new Location(endWorld, 0.5, 64, 0.5);

        CreatureSpawnEvent event = fireSpawnEvent(loc, CreatureSpawnEvent.SpawnReason.NATURAL);

        assertTrue(event.isCancelled(),
                "A natural phantom spawn in The End above a non-allowed surface block (obsidian) must be vetoed");
    }

    @Test
    void naturalSpawnAboveChorusPlantIsAllowed() {
        setFloor(endWorld, 63, Material.CHORUS_PLANT);
        Location loc = new Location(endWorld, 0.5, 64, 0.5);

        CreatureSpawnEvent event = fireSpawnEvent(loc, CreatureSpawnEvent.SpawnReason.NATURAL);

        assertFalse(event.isCancelled(), "A natural phantom spawn above chorus plant must be allowed");
    }

    @Test
    void naturalSpawnOverVoidWithNoSurfaceIsCancelled() {
        // No floor set at all: every block below is air/void.
        Location loc = new Location(endWorld, 0.5, 64, 0.5);

        CreatureSpawnEvent event = fireSpawnEvent(loc, CreatureSpawnEvent.SpawnReason.NATURAL);

        assertTrue(event.isCancelled(), "A natural phantom spawn over open void (no surface at all) must be vetoed");
    }

    @Test
    void spawnEggInOverworldIsNotGoverned() {
        // Deliberate/explicit spawn causes (eggs, commands, plugins) are
        // intentionally left alone by PhantomSpawnListener - this is
        // documented, load-bearing behavior, not an oversight, so it needs
        // its own regression test as much as the vetoes do.
        setFloor(overworld, 63, Material.GRASS_BLOCK);
        Location loc = new Location(overworld, 0.5, 64, 0.5);

        CreatureSpawnEvent event = fireSpawnEvent(loc, CreatureSpawnEvent.SpawnReason.SPAWNER_EGG);

        assertFalse(event.isCancelled(),
                "A SPAWNER_EGG-caused phantom spawn must NOT be vetoed - only natural spawn causes are governed");
    }

    @Test
    void commandSpawnInOverworldIsNotGoverned() {
        setFloor(overworld, 63, Material.GRASS_BLOCK);
        Location loc = new Location(overworld, 0.5, 64, 0.5);

        CreatureSpawnEvent event = fireSpawnEvent(loc, CreatureSpawnEvent.SpawnReason.COMMAND);

        assertFalse(event.isCancelled(), "A COMMAND-caused phantom spawn must NOT be vetoed");
    }

    @Test
    void nonPhantomEntitiesAreIgnoredEntirely() {
        setFloor(overworld, 63, Material.GRASS_BLOCK);
        Location loc = new Location(overworld, 0.5, 64, 0.5);
        org.bukkit.entity.Zombie zombie = overworld.spawn(loc, org.bukkit.entity.Zombie.class);
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);

        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "Non-phantom mobs must never be touched by this listener");
    }

    @Test
    void reinforcementsSpawnReasonIsGoverned() {
        // REINFORCEMENTS is one of the three reasons isNaturalSpawnCause()
        // treats as "natural" alongside NATURAL/DEFAULT - regression test
        // for that specific branch, since it's easy to accidentally narrow
        // that switch to just NATURAL during a refactor.
        Location loc = new Location(overworld, 0.5, 64, 0.5);
        setFloor(overworld, 63, Material.GRASS_BLOCK);

        CreatureSpawnEvent event = fireSpawnEvent(loc, CreatureSpawnEvent.SpawnReason.REINFORCEMENTS);

        assertTrue(event.isCancelled(), "REINFORCEMENTS is a natural spawn cause and must be governed like NATURAL");
    }
}