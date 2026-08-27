package com.anarchyphantoms.phantomcontrol;

import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.extension.ExtensionService;

import java.util.logging.Level;

/**
 * All access to the Plan Player Analytics API lives in this one class, kept
 * deliberately separate from every other class in the plugin.
 *
 * Plan is an optional soft-dependency (see plugin.yml's softdepend), which
 * means its classes (com.djrapitops.plan.*) may simply not exist on the
 * classpath at all if the server owner hasn't installed Plan. If any of
 * those imports were used directly in AnarchyPhantomsPlugin, the JVM would
 * throw NoClassDefFoundError the moment that class is loaded/verified -
 * regardless of whether the code path that uses Plan actually runs - which
 * would break the entire plugin for anyone without Plan installed. Isolating
 * every Plan import in this one class means that class (and only this class)
 * fails to load if Plan is absent, and every call site here is wrapped so
 * that failure is caught and treated as "Plan isn't available", not a hard
 * crash.
 *
 * This mirrors the "Getting started" pattern from Plan's own DataExtension
 * API documentation.
 */
final class PlanHook {

    /**
     * Capability required for the DataExtension API (provider annotations,
     * @PluginInfo, etc.) used by AnarchyPhantomsDataExtension. Checked before
     * registering so a very old Plan build without this API is treated the
     * same as Plan not being installed at all, rather than throwing deeper
     * inside the registration call.
     */
    private static final String REQUIRED_CAPABILITY = "DATA_EXTENSION_VALUES";

    private final AnarchyPhantomsPlugin plugin;
    private final PhantomStatsTracker statsTracker;

    PlanHook(AnarchyPhantomsPlugin plugin, PhantomStatsTracker statsTracker) {
        this.plugin = plugin;
        this.statsTracker = statsTracker;
    }

    /**
     * Attempts to register AnarchyPhantoms' DataExtension with Plan, and
     * arms a listener so the extension re-registers itself if Plan later
     * reloads (e.g. via Plan's own /plan reload). Safe to call unconditionally
     * from onEnable regardless of whether Plan is installed - every failure
     * mode (missing classes, Plan present but disabled, an implementation
     * bug in the extension itself) is caught here and logged at a level that
     * won't alarm admins who never intended to use Plan at all.
     */
    void hookIntoPlan() {
        if (!capabilityAvailable()) {
            plugin.getLogger().info("Plan not detected (or too old to support DataExtension API) - "
                    + "skipping Plan integration. This is not an error; Plan is fully optional.");
            return;
        }

        registerDataExtension();
        listenForPlanReloads();
    }

    private boolean capabilityAvailable() {
        try {
            CapabilityService capabilities = CapabilityService.getInstance();
            return capabilities.hasCapability(REQUIRED_CAPABILITY);
        } catch (NoClassDefFoundError | IllegalStateException notInstalledOrNotReady) {
            return false;
        }
    }

    private void registerDataExtension() {
        try {
            ExtensionService.getInstance().register(new AnarchyPhantomsDataExtension(statsTracker));
            plugin.getLogger().info("Registered AnarchyPhantoms stats with Plan Player Analytics.");
        } catch (NoClassDefFoundError planIsNotInstalled) {
            // Shouldn't normally happen given the capability check above,
            // but guarded independently in case Plan's classes partially
            // unload between the check and this call (e.g. mid-reload race).
            plugin.getLogger().info("Plan classes not present - skipping Plan integration.");
        } catch (IllegalStateException planIsNotEnabled) {
            plugin.getLogger().info("Plan is installed but not currently enabled - skipping Plan integration for now.");
        } catch (IllegalArgumentException dataExtensionImplementationIsInvalid) {
            // Indicates a bug in AnarchyPhantomsDataExtension's annotations
            // (see that class's own unit test for the same validation run
            // ahead of time). Logged at WARNING since this one *is* an
            // AnarchyPhantoms-side bug, not an environment/absence issue.
            plugin.getLogger().log(Level.WARNING,
                    "AnarchyPhantoms' Plan DataExtension failed validation - Plan integration disabled.",
                    dataExtensionImplementationIsInvalid);
        }
    }

    /**
     * Plan can be reloaded independently of this plugin (e.g. an admin runs
     * /plan reload). When that happens Plan drops previously-registered
     * extensions, so without this listener AnarchyPhantoms' panel would
     * silently disappear from Plan's web UI until the next full server
     * restart.
     */
    private void listenForPlanReloads() {
        try {
            CapabilityService.getInstance().registerEnableListener(isPlanEnabled -> {
                if (isPlanEnabled) {
                    registerDataExtension();
                }
            });
        } catch (NoClassDefFoundError | IllegalStateException ignored) {
            // Plan disappeared between hookIntoPlan()'s check and here
            // (very small window); nothing to listen to anymore.
        }
    }
}