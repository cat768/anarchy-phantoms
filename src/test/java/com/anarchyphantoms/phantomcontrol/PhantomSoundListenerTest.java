package com.anarchyphantoms.phantomcontrol;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Phantom;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PhantomMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral coverage for {@link PhantomSoundListener}: phantoms should be
 * silent until provoked, then audible afterward. Silencing is done via
 * LivingEntity#setSilent, so these tests assert directly on
 * phantom.isSilent() after firing the same events the real server would.
 */
class PhantomSoundListenerTest extends PluginTestBase {

    private Phantom spawnPhantom() {
        setFloor(endWorld, 63, Material.END_STONE);
        Location loc = new Location(endWorld, 0.5, 64, 0.5);
        Phantom phantom = endWorld.spawn(loc, Phantom.class);
        // World#spawn's CreatureSpawnEvent path doesn't always route through
        // EntitySpawnEvent in every mock/vanilla implementation the same
        // way, so fire it explicitly here to exercise exactly the listener
        // method under test, independent of that plumbing.
        server.getPluginManager().callEvent(new EntitySpawnEvent(phantom));
        return phantom;
    }

    @Test
    void freshlySpawnedPhantomIsSilent() {
        Phantom phantom = spawnPhantom();

        assertTrue(phantom.isSilent(), "A freshly spawned phantom must start silent");
    }

    @Test
    void phantomIsUnsilencedWhenProvoked() {
        Phantom phantom = spawnPhantom();
        PlayerMock attacker = server.addPlayer();

        // simulateDamage(), not damage(): plain damage() only mutates health
        // and never fires an EntityDamageByEntityEvent, so the listener
        // under test would never see the hit and the phantom would stay
        // silent regardless of whether the plugin logic is correct.
        // simulateDamage() lives only on the concrete PhantomMock, not the
        // Phantom interface, so it needs a cast; world.spawn(loc,
        // Phantom.class) is registered to always return a PhantomMock at
        // runtime, so this is safe.
        ((PhantomMock) phantom).simulateDamage(2.0, attacker);

        assertFalse(phantom.isSilent(), "A provoked phantom must have its screech un-silenced");
    }

    @Test
    void unprovokedDamageFromNonPlayerKeepsPhantomSilent() {
        Phantom phantom = spawnPhantom();
        Phantom attacker = spawnPhantom();

        ((PhantomMock) phantom).simulateDamage(1.0, attacker);

        assertTrue(phantom.isSilent(),
                "Damage from a non-player source must not un-silence the phantom (tracker never marked it provoked)");
    }
}