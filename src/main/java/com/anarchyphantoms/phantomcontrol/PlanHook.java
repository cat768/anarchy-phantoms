package com.anarchyphantoms.phantomcontrol;

import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.extension.ExtensionService;

/**
 * Sole entry point into the Plan (player analytics) DataExtension API.
 *
 * Every class that imports {@code com.djrapitops.plan.*} lives either in
 * this file or in {@link AnarchyPhantomsDataExtension}. Keeping all Plan
 * imports confined to these two classes - and never referencing either of
 * them from a class that Bukkit loads unconditionally - is what lets Plan
 * stay a true optional soft-dependency: if Plan's jar isn't on the server,
 * the JVM never needs to resolve these classes at all, so nothing throws
 * {@link NoClassDefFoundError} outside of the try/catch that already wraps
 * construction of this class in {@link AnarchyPhantomsPlugin#onEnable()}.
 *
 * @see <a href="https://github.com/plan-player-analytics/Plan/wiki/DataExtension-API---Getting-started">
 *      Plan's DataExtension API - Getting started</a>
 */
public final class PlanHook {

    /**
     * Capability required for registering a DataExtension at all. Plan
     * documents additional capability strings for specific features (e.g.
     * per-tab placement); this plugin's extension only uses the base
     * provider annotations, so this single check is sufficient.
     */
    private static final String DATA_EXTENSION_CAPABILITY = "DATA_EXTENSION_VALUES";

    private final AnarchyPhantomsPlugin plugin;
    private final PhantomPlayerStats playerStats;

    public PlanHook(AnarchyPhantomsPlugin plugin, PhantomPlayerStats playerStats) {
        this.plugin = plugin;
        this.playerStats = playerStats;
    }

    /**
     * Registers this plugin's DataExtension with Plan, if Plan is present
     * and reports the capability this integration needs. Safe to call even
     * when Plan is absent/disabled - every failure mode here is caught and
     * logged rather than propagated, since Plan integration is a nice-to-have,
     * never something the rest of the plugin should depend on succeeding.
     */
    public void hookIntoPlan() {
        if (!hasRequiredCapability()) {
            plugin.getLogger().info("AnarchyPhantoms: Plan is installed but does not report the "
                    + DATA_EXTENSION_CAPABILITY + " capability - skipping Plan integration.");
            return;
        }
        registerDataExtension();
        listenForPlanReloads();
    }

    private boolean hasRequiredCapability() {
        CapabilityService capabilities = CapabilityService.getInstance();
        return capabilities.hasCapability(DATA_EXTENSION_CAPABILITY);
    }

    private void registerDataExtension() {
        try {
            ExtensionService.getInstance().register(new AnarchyPhantomsDataExtension(playerStats));
            plugin.getLogger().info("AnarchyPhantoms: registered with Plan - phantom stats will appear on player pages.");
        } catch (IllegalStateException planNotEnabled) {
            plugin.getLogger().info("AnarchyPhantoms: Plan is installed but not enabled - skipping Plan integration.");
        } catch (IllegalArgumentException invalidExtension) {
            // Indicates a bug in AnarchyPhantomsDataExtension's annotations
            // (e.g. a bad return type), not a runtime/environment issue -
            // worth a warning rather than a quiet info line.
            plugin.getLogger().warning("AnarchyPhantoms: Plan rejected the DataExtension implementation: "
                    + invalidExtension.getMessage());
        }
    }

    /**
     * Plan can be reloaded independently of this plugin (e.g. via its own
     * reload command or a PlugMan-style plugin reload). When that happens
     * Plan drops previously-registered extensions, so this re-registers
     * ours whenever Plan reports coming back up.
     */
    private void listenForPlanReloads() {
        CapabilityService.getInstance().registerEnableListener(isPlanEnabled -> {
            if (isPlanEnabled) {
                registerDataExtension();
            }
        });
    }
}