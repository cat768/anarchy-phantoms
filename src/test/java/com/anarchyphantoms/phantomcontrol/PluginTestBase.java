package com.anarchyphantoms.phantomcontrol;

import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Shared MockBukkit fixture for every behavioral test in this package.
 *
 * <p>Boots a fresh in-memory mock server + loads a fresh instance of the
 * actual plugin jar-under-test before each test, and tears both down
 * afterward. Deliberately per-test (not per-class) so tests can never leak
 * PDC/tracker/config state into one another via a shared entity or world -
 * every test gets a plugin instance that has never seen any event before it.
 *
 * <p>Also exposes one overworld-like and one End-like {@link WorldMock},
 * since almost every behavioral test in this suite turns on which
 * dimension/environment a spawn or event happens in.
 */
abstract class PluginTestBase {

    protected ServerMock server;
    protected AnarchyPhantomsPlugin plugin;

    /** A NORMAL-environment world, standing in for the overworld. */
    protected WorldMock overworld;

    /** A THE_END-environment world. */
    protected WorldMock endWorld;

    @BeforeEach
    void setUpMockServer() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(AnarchyPhantomsPlugin.class);

        overworld = server.addSimpleWorld("overworld_test");
        overworld.setEnvironment(World.Environment.NORMAL);

        endWorld = server.addSimpleWorld("end_test");
        endWorld.setEnvironment(World.Environment.THE_END);
    }

    @AfterEach
    void tearDownMockServer() {
        MockBukkit.unmock();
    }

    /**
     * Fills a flat plane of the given world at the given Y level with the
     * given surface material, for tests that need a specific block directly
     * below a spawn location. WorldMock generates as air/void by default, so
     * without this, every "is this above an allowed surface" check would
     * trivially see nothing but air.
     */
    protected static void setFloor(WorldMock world, int y, Material material) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.getBlockAt(x, y, z).setType(material);
            }
        }
    }
}
