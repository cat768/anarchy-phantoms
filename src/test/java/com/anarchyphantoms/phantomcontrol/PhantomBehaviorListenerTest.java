package com.anarchyphantoms.phantomcontrol;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Phantom;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral coverage for {@link PhantomBehaviorListener}: "passive until
 * attacked" is the plugin's headline feature, and previously had zero
 * assertions anywhere in CI - only that the server didn't crash while the
 * listener was registered.
 *
 * These tests fire the real EntityTargetLivingEntityEvent and
 * EntityDamageByEntityEvent through the mock PluginManager, exactly as
 * Paper would when a phantom's AI tries to acquire a target or a player
 * lands a hit, and assert on event.isCancelled() / phantom.getTarget().
 */
class PhantomBehaviorListenerTest extends PluginTestBase {

    private Phantom spawnPhantom() {
        setFloor(endWorld, 63, Material.END_STONE);
        Location loc = new Location(endWorld, 0.5, 64, 0.5);
        return endWorld.spawn(loc, Phantom.class);
    }

    @Test
    void unprovokedPhantomCannotAcquireATarget() {
        Phantom phantom = spawnPhantom();
        PlayerMock player = server.addPlayer();

        EntityTargetLivingEntityEvent event =
                new EntityTargetLivingEntityEvent(phantom, player, EntityTargetLivingEntityEvent.TargetReason.CLOSEST_PLAYER);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "An unprovoked phantom must never be allowed to acquire a target");
    }

    @Test
    void provokedPhantomCanAcquireATarget() {
        Phantom phantom = spawnPhantom();
        PlayerMock attacker = server.addPlayer();

        // Provoke the phantom the same way a real hit would: call the
        // actual public Bukkit API a sword-swing ultimately invokes, which
        // fires a real EntityDamageByEntityEvent through the plugin's
        // registered listener - rather than hand-constructing that event
        // (whose exact constructor overload set has changed across API
        // versions) directly in the test.
        phantom.damage(2.0, attacker);

        EntityTargetLivingEntityEvent targetEvent =
                new EntityTargetLivingEntityEvent(phantom, attacker, EntityTargetLivingEntityEvent.TargetReason.CLOSEST_PLAYER);
        server.getPluginManager().callEvent(targetEvent);

        assertFalse(targetEvent.isCancelled(), "A provoked phantom must be allowed to acquire a target");
    }

    @Test
    void damageFromNonPlayerDoesNotProvoke() {
        // e.g. void damage, fire, another mob - resolvePlayerAttacker()
        // must return null and the phantom must stay passive.
        Phantom phantom = spawnPhantom();
        Phantom otherPhantom = spawnPhantom();

        phantom.damage(1.0, otherPhantom);

        PlayerMock somePlayer = server.addPlayer();
        EntityTargetLivingEntityEvent targetEvent =
                new EntityTargetLivingEntityEvent(phantom, somePlayer, EntityTargetLivingEntityEvent.TargetReason.CLOSEST_PLAYER);
        server.getPluginManager().callEvent(targetEvent);

        assertTrue(targetEvent.isCancelled(), "Damage from a non-player source must not provoke the phantom");
    }

    @Test
    void onlyTheDamagedPhantomBecomesProvokedNotOthersNearby() {
        Phantom attacked = spawnPhantom();
        Phantom bystander = spawnPhantom();
        PlayerMock attacker = server.addPlayer();

        attacked.damage(2.0, attacker);

        EntityTargetLivingEntityEvent bystanderTarget =
                new EntityTargetLivingEntityEvent(bystander, attacker, EntityTargetLivingEntityEvent.TargetReason.CLOSEST_PLAYER);
        server.getPluginManager().callEvent(bystanderTarget);

        assertTrue(bystanderTarget.isCancelled(),
                "Attacking one phantom must not provoke a different, untouched phantom nearby");
    }
}
