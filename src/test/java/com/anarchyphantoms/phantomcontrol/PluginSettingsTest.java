package com.anarchyphantoms.phantomcontrol;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PluginSettings}'s config-parsing/validation logic:
 * clamping and safe-fallback behavior that would previously have gone
 * completely unverified (a broken config.yml or a bad admin edit only ever
 * showed up as either a silent behavior change or a wall-of-text warning in
 * a server log nobody was watching in CI).
 */
class PluginSettingsTest extends PluginTestBase {

    @Test
    void defaultConfigLoadsExpectedValues() {
        PluginSettings settings = plugin.getSettings();

        assertTrue(settings.isBlockOverworldSpawns());
        assertTrue(settings.isOnlySpawnInEnd());
        assertTrue(settings.isPassiveUntilAttacked());
        assertTrue(settings.isSilenceScreechUntilAttacked());
        assertTrue(settings.isEndSpawningEnabled());
        assertEquals(6000L, settings.getProvokedDurationTicks());
        assertTrue(settings.getAllowedSurfaceBlocks().contains(Material.END_STONE));
        assertTrue(settings.getAllowedSurfaceBlocks().contains(Material.CHORUS_PLANT));
        assertTrue(settings.getAllowedSurfaceBlocks().contains(Material.CHORUS_FLOWER));
    }

    @Test
    void surfaceCheckDepthIsClampedToAtLeastEndSpawnerMaxHeight() {
        // Documented invariant in PluginSettings#reload: a configured depth
        // shallower than PhantomEndSpawner.MAX_HEIGHT_ABOVE_GROUND must be
        // clamped up, or every End-spawned phantom would be silently
        // vetoed by PhantomSpawnListener's own downward scan. This is
        // exactly the kind of cross-class invariant that a boot-only smoke
        // test can never catch, since nothing ever fails to *start*.
        plugin.getConfig().set("phantoms.surface-check-depth", 5);
        plugin.getSettings().reload();

        assertTrue(plugin.getSettings().getSurfaceCheckDepth() >= PhantomEndSpawner.MAX_HEIGHT_ABOVE_GROUND,
                "surface-check-depth must never be allowed below End-spawner's max spawn height");
    }

    @Test
    void surfaceCheckDepthAboveTheFloorIsRespectedAsIs() {
        int generous = PhantomEndSpawner.MAX_HEIGHT_ABOVE_GROUND + 50;
        plugin.getConfig().set("phantoms.surface-check-depth", generous);
        plugin.getSettings().reload();

        assertEquals(generous, plugin.getSettings().getSurfaceCheckDepth());
    }

    @Test
    void endSpawnChanceIsClampedToZeroOneRange() {
        plugin.getConfig().set("phantoms.end-spawning.spawn-chance", 5.0);
        plugin.getSettings().reload();
        assertEquals(1.0, plugin.getSettings().getEndSpawnChance());

        plugin.getConfig().set("phantoms.end-spawning.spawn-chance", -3.0);
        plugin.getSettings().reload();
        assertEquals(0.0, plugin.getSettings().getEndSpawnChance());
    }

    @Test
    void unknownMaterialNamesInAllowedSurfaceBlocksAreSkippedNotFatal() {
        plugin.getConfig().set("phantoms.allowed-surface-blocks",
                java.util.List.of("END_STONE", "THIS_IS_NOT_A_REAL_MATERIAL"));
        plugin.getSettings().reload();

        assertTrue(plugin.getSettings().getAllowedSurfaceBlocks().contains(Material.END_STONE));
        assertEquals(1, plugin.getSettings().getAllowedSurfaceBlocks().size(),
                "An unrecognized material name must be skipped, not crash reload() or silently allow everything");
    }

    @Test
    void emptyAllowedSurfaceBlocksFallsBackToEndDefaults() {
        plugin.getConfig().set("phantoms.allowed-surface-blocks", java.util.List.of());
        plugin.getSettings().reload();

        assertTrue(plugin.getSettings().getAllowedSurfaceBlocks().contains(Material.END_STONE));
        assertTrue(plugin.getSettings().getAllowedSurfaceBlocks().contains(Material.CHORUS_PLANT));
        assertTrue(plugin.getSettings().getAllowedSurfaceBlocks().contains(Material.CHORUS_FLOWER));
    }

    @Test
    void debugRuntimeOverrideTakesPrecedenceOverConfigAndSurvivesReload() {
        plugin.getConfig().set("debug.enabled", false);
        plugin.getSettings().reload();
        assertEquals(false, plugin.getSettings().isDebugEnabled());

        plugin.getSettings().setDebugRuntimeOverride(true);
        assertTrue(plugin.getSettings().isDebugEnabled(), "Runtime override must take precedence over config.yml");

        // reload() must not clobber a live runtime override (e.g. from
        // /ap reload while an admin has debug toggled on for a live
        // investigation) - this is documented behavior in PluginSettings.
        plugin.getSettings().reload();
        assertTrue(plugin.getSettings().isDebugEnabled(),
                "reload() must not silently clear a runtime debug override");
    }

    @Test
    void maxPhantomsPerPlayerNeverNegative() {
        plugin.getConfig().set("phantoms.end-spawning.max-phantoms-per-player", -10);
        plugin.getSettings().reload();

        assertEquals(0, plugin.getSettings().getMaxPhantomsPerPlayer());
    }
}
