package com.anarchyphantoms.phantomcontrol;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral coverage for {@link PhantomEndSpawner}: the class responsible
 * for actively spawning phantoms in The End at all (vanilla has no natural
 * End spawn cycle for phantoms - see the class javadoc on PhantomEndSpawner
 * itself). Prior to this suite, nothing ever verified that entering The End
 * actually starts the spawn-attempt cycle, that leaving stops it, or that
 * the per-player phantom cap is honored.
 *
 * <p><b>Scope note:</b> {@code tryEndSpawn} itself is private and driven by
 * a per-player {@code Player#getScheduler()} (Paper's EntityScheduler, not
 * the plain Bukkit scheduler). If a given MockBukkit release's fake
 * scheduler doesn't support ticking EntityScheduler-based repeating tasks,
 * MockBukkit's documented behavior is to throw
 * {@code UnimplementedOperationException} (a {@code TestAbortedException}
 * subtype), which JUnit reports as a skipped test rather than a failure -
 * see https://docs.mockbukkit.org. These tests are written to degrade that
 * way rather than hard-fail if that specific mocking gap exists in the
 * pinned MockBukkit version; if they start reporting as skipped, that's a
 * signal to open an issue against MockBukkit or find its equivalent
 * EntityScheduler tick-driving API for the pinned version, not a signal
 * that this plugin's logic is broken.
 */
class PhantomEndSpawnerTest extends PluginTestBase {

    private PlayerMock movePlayerToEnd(PlayerMock player) {
        setFloor(endWorld, 63, Material.END_STONE);
        World previousWorld = player.getWorld();
        player.setLocation(new Location(endWorld, 0.5, 70, 0.5));
        server.getPluginManager().callEvent(new PlayerChangedWorldEvent(player, previousWorld));
        return player;
    }

    @Test
    void enteringTheEndEventuallyProducesAPhantomWhenChanceIsMaxed() {
        // Force the roll to always succeed and shrink the cap/radius knobs
        // to their simplest form, so this test asserts the actual spawn
        // pipeline (join The End -> periodic check -> phantom appears)
        // rather than getting lucky/unlucky on the default 15% chance.
        plugin.getConfig().set("phantoms.end-spawning.spawn-chance", 1.0);
        plugin.getConfig().set("phantoms.end-spawning.max-phantoms-per-player", 4);
        plugin.getSettings().reload();

        PlayerMock player = server.addPlayer();
        movePlayerToEnd(player);

        // CHECK_INTERVAL_TICKS is 200 (10s); run well past one full cycle
        // plus the internal 12-sample location-pick retry budget so a
        // single unlucky column sample can't flake the test.
        server.getScheduler().performTicks(210L);

        long phantomCount = endWorld.getEntitiesByClass(org.bukkit.entity.Phantom.class).size();
        assertTrue(phantomCount >= 1,
                "With spawn-chance forced to 1.0, at least one phantom should have spawned near the player in The End "
                        + "after a full check interval - got " + phantomCount);
    }

    @Test
    void spawnChanceOfZeroNeverSpawnsAPhantom() {
        plugin.getConfig().set("phantoms.end-spawning.spawn-chance", 0.0);
        plugin.getSettings().reload();

        PlayerMock player = server.addPlayer();
        movePlayerToEnd(player);

        server.getScheduler().performTicks(210L);

        long phantomCount = endWorld.getEntitiesByClass(org.bukkit.entity.Phantom.class).size();
        assertEquals(0, phantomCount, "With spawn-chance forced to 0.0, no phantom should ever spawn");
    }

    @Test
    void endSpawningDisabledInConfigProducesNoSpawns() {
        plugin.getConfig().set("phantoms.end-spawning.enabled", false);
        plugin.getConfig().set("phantoms.end-spawning.spawn-chance", 1.0);
        plugin.getSettings().reload();

        PlayerMock player = server.addPlayer();
        movePlayerToEnd(player);

        server.getScheduler().performTicks(210L);

        long phantomCount = endWorld.getEntitiesByClass(org.bukkit.entity.Phantom.class).size();
        assertEquals(0, phantomCount,
                "end-spawning.enabled=false must suppress all active End spawning, even with chance=1.0");
    }

    @Test
    void perPlayerPhantomCapIsRespected() {
        plugin.getConfig().set("phantoms.end-spawning.spawn-chance", 1.0);
        plugin.getConfig().set("phantoms.end-spawning.max-phantoms-per-player", 1);
        plugin.getConfig().set("phantoms.end-spawning.spawn-check-radius", 32.0);
        plugin.getSettings().reload();

        PlayerMock player = server.addPlayer();
        movePlayerToEnd(player);

        // Run many cycles - if the cap is honored, the count must plateau
        // at max-phantoms-per-player (1) instead of growing unboundedly.
        server.getScheduler().performTicks(200L * 6);

        long phantomCount = endWorld.getNearbyEntities(player.getLocation(), 32, 32, 32,
                entity -> entity.getType() == EntityType.PHANTOM).size();
        assertTrue(phantomCount <= 1,
                "max-phantoms-per-player=1 must cap nearby phantoms at 1 regardless of how many check cycles run - got "
                        + phantomCount);
    }

    @Test
    void leavingTheEndStopsFurtherSpawnAttempts() {
        plugin.getConfig().set("phantoms.end-spawning.spawn-chance", 1.0);
        plugin.getSettings().reload();

        PlayerMock player = server.addPlayer();
        movePlayerToEnd(player);

        // Leave The End immediately, before any check interval elapses.
        server.getPluginManager().callEvent(new PlayerChangedWorldEvent(player, endWorld));
        player.setLocation(new Location(overworld, 0.5, 70, 0.5));

        server.getScheduler().performTicks(210L);

        long phantomCount = endWorld.getEntitiesByClass(org.bukkit.entity.Phantom.class).size();
        assertEquals(0, phantomCount,
                "No End-spawner phantom should appear once the player has left The End, even with chance=1.0");
    }
}
