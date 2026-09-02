package com.anarchyphantoms.phantomcontrol;

import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.extension.ExtensionService;
import com.djrapitops.plan.query.QueryService;

import java.util.Map;
import java.util.UUID;
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

    /**
     * Capability required for the Query API (QueryService#execute/query,
     * used by PlanStatsRepository to persist stats into Plan's own
     * database). Checked independently of REQUIRED_CAPABILITY: a Plan build
     * new enough for DataExtension but too old for Query API should still
     * get the Plan panel integration, just without persistence - the two
     * features aren't coupled from the server owner's perspective, so
     * their availability checks shouldn't be coupled here either.
     */
    private static final String QUERY_API_CAPABILITY = "QUERY_API";

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
        setUpPersistence();
    }

    /**
     * Wires PhantomStatsTracker up to persist into Plan's own database, and
     * immediately hydrates it from whatever was already there - both are
     * skipped (statsTracker is simply left running in-memory-only, exactly
     * as it behaved before this feature existed) if the Query API
     * capability isn't available, e.g. an older Plan build that still
     * supports DataExtension but predates Query API. This mirrors
     * registerDataExtension()'s own capability-gated, exception-guarded
     * shape rather than assuming Query API exists just because
     * capabilityAvailable() (which only checks DATA_EXTENSION_VALUES)
     * returned true above.
     */
    private void setUpPersistence() {
        if (!queryApiAvailable()) {
            plugin.getLogger().info("Plan's Query API is not available (older Plan build?) - "
                    + "AnarchyPhantoms stats will not persist across restarts, but the Plan panel integration above still works.");
            return;
        }

        try {
            PlanStatsRepository repository = new PlanStatsRepository(QueryService.getInstance());
            hydrateFromRepository(repository);
            statsTracker.setPersistenceSink(repository);
            plugin.getLogger().info("AnarchyPhantoms phantom stats will now persist in Plan's database across restarts.");
        } catch (NoClassDefFoundError planIsNotInstalled) {
            // Same defensive shadow as registerDataExtension() - guards
            // against Plan's classes disappearing mid-reload between the
            // capability check above and this call.
            plugin.getLogger().info("Plan classes not present - skipping stats persistence.");
        } catch (IllegalStateException planIsNotEnabled) {
            plugin.getLogger().info("Plan is installed but not currently enabled - skipping stats persistence for now.");
        } catch (RuntimeException persistenceSetupFailed) {
            // Covers unexpected failures from table creation/hydration
            // (e.g. a misbehaving custom database driver) without ever
            // letting a Plan-side problem prevent AnarchyPhantoms itself
            // from finishing onEnable - persistence is strictly additive.
            plugin.getLogger().log(Level.WARNING,
                    "Failed to set up AnarchyPhantoms stats persistence in Plan's database - "
                            + "stats will remain in-memory-only for this session.",
                    persistenceSetupFailed);
        }
    }

    private boolean queryApiAvailable() {
        try {
            return CapabilityService.getInstance().hasCapability(QUERY_API_CAPABILITY);
        } catch (NoClassDefFoundError | IllegalStateException notInstalledOrNotReady) {
            return false;
        }
    }

    /**
     * Loads every persisted counter back into statsTracker before wiring up
     * the sink for new writes, so a player who already has history isn't
     * shown all-zero stats (in-game or in Plan) until they trigger a fresh
     * event - see PhantomStatsTracker#restorePlayerCounters/restoreServerTotals.
     * Runs synchronously during onEnable (see PlanStatsRepository's class
     * javadoc for why the blocking reads here are safe at this specific
     * point in the plugin lifecycle).
     */
    private void hydrateFromRepository(PlanStatsRepository repository) {
        Map<UUID, PhantomStatsTracker.PersistedPlayerCounters> playerCounters = repository.loadAllPlayerCounters();
        for (Map.Entry<UUID, PhantomStatsTracker.PersistedPlayerCounters> entry : playerCounters.entrySet()) {
            statsTracker.restorePlayerCounters(entry.getKey(), entry.getValue());
        }
        statsTracker.restoreServerTotals(repository.loadServerTotals());
        plugin.getLogger().info("Restored AnarchyPhantoms stats for " + playerCounters.size()
                + " player(s) from Plan's database.");
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
     *
     * Deliberately only re-registers the DataExtension, not
     * setUpPersistence(): the extension registry is what Plan actually
     * clears on reload, but statsTracker's persistence sink and its
     * already-hydrated in-memory counters are untouched by a Plan reload -
     * re-running setUpPersistence() here would re-hydrate from the
     * database and clobber every counter update this session has recorded
     * in memory since the plugin actually started, silently rewinding
     * live totals back to their last-persisted values.
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