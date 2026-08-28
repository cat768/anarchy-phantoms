package com.anarchyphantoms.phantomcontrol;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Actively spawns phantoms above players in The End.
 *
 * Vanilla phantoms only have a NATURAL spawn cycle in the Overworld, gated by
 * a player's "time since last rest" (72000 ticks / 3 in-game days). There is
 * no vanilla spawn cycle for phantoms in The End at all - PhantomSpawnListener
 * only ever *filters* CreatureSpawnEvent, it never generates one, so without
 * this class no phantom ever naturally appears in The End, regardless of how
 * PhantomSpawnListener is configured.
 *
 * This mirrors 2b2t's own "Phantoms In The End" behavior: phantoms spawn in
 * The End above endstone/chorus blocks, are not hostile until attacked, and
 * critically are NOT gated by the Overworld sleep/insomnia timer - The End
 * has no day/night cycle or beds, so a sleep-debt requirement would make End
 * spawning nearly impossible in practice. Eligibility here is based purely on
 * dimension, surface, and sky access.
 *
 * Each online player gets their own per-player repeating task via
 * Player#getScheduler() (an EntityScheduler), which is the Folia-safe
 * equivalent of a global "for each online player" BukkitScheduler sweep -
 * see the design note in PhantomSoundListener for why a single global task
 * touching arbitrary entities/players is unsafe under Folia's regionized
 * threading model.
 *
 * The task is only alive while a player is actually in THE_END. There is
 * no vanilla or plugin-provided phantom spawn cycle outside The End (see
 * class-level note above), so a player in the Overworld or Nether has zero
 * chance of ever passing tryEndSpawn's environment check - running the task
 * for them anyway would just roll dice against a check that can only ever
 * fail, forever, for the entire time they're not in The End. Instead:
 *  - PlayerChangedWorldEvent starts the task on entering THE_END and stops
 *    (cancels) it on leaving.
 *  - PlayerJoinEvent/startTaskForExisting start it immediately if the
 *    player is already in THE_END at join/reload time.
 *  - PlayerQuitEvent (and the EntityScheduler's own retired-callback) clean
 *    up the tracking map so it can't leak entries for disconnected players.
 * This removes the "not in The End" no-op entirely for anyone not in The
 * End, rather than just silencing its debug output.
 */
public final class PhantomEndSpawner implements Listener {

    private static final long CHECK_INTERVAL_TICKS = 200L; // every 10 seconds

    /**
     * Highest a spawned phantom is ever placed above the real ground
     * (see pickSpawnLocation). PluginSettings clamps surface-check-depth
     * to at least this value so PhantomSpawnListener's downward veto scan
     * can never be shallower than the height phantoms actually spawn at -
     * that mismatch previously caused every End spawn to be silently
     * vetoed regardless of end-spawning settings.
     */
    static final int MAX_HEIGHT_ABOVE_GROUND = 30;

    private final AnarchyPhantomsPlugin plugin;
    private final PhantomDebugNotifier debugNotifier;
    private final PhantomSpawnCauseTag spawnCauseTag;
    private final PhantomStatsTracker statsTracker;
    private final Random random = new Random();

    /**
     * Tracks the running task per player, keyed by UUID, so we can cancel it
     * on world-leave/quit and avoid double-starting it if events race (e.g.
     * a stray extra PlayerChangedWorldEvent). Only ever contains entries for
     * players currently believed to be in THE_END with a live task.
     */
    private final Map<UUID, ScheduledTask> activeTasks = new ConcurrentHashMap<>();

    public PhantomEndSpawner(AnarchyPhantomsPlugin plugin) {
        this.plugin = plugin;
        this.debugNotifier = plugin.getDebugNotifier();
        this.spawnCauseTag = plugin.getSpawnCauseTag();
        this.statsTracker = plugin.getStatsTracker();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getWorld().getEnvironment() == World.Environment.THE_END) {
            startTaskFor(player);
        }
    }

    /**
     * Starts (or stops) the task in response to a dimension change. This is
     * the main entry/exit point in normal play - joining already-in-The-End
     * is comparatively rare and handled separately by onPlayerJoin above.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (player.getWorld().getEnvironment() == World.Environment.THE_END) {
            startTaskFor(player);
        } else {
            stopTaskFor(player.getUniqueId());
        }
    }

    /**
     * Stops leaking a tracking-map entry for a player who has disconnected.
     * The EntityScheduler's own retired-callback already stops the
     * scheduled task itself when a player quits, so this is bookkeeping
     * cleanup only, not what actually halts execution.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        activeTasks.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Starts this player's own repeating eligibility/spawn check, but only
     * if they're not already running one (covers onPlayerJoin firing for a
     * player already in THE_END, e.g. after a /reload while inside it).
     * Using Player#getScheduler() means the callback only ever runs on the
     * thread that currently owns this specific player, following them
     * across regions on Folia, and behaves like a normal repeating task on
     * standard Paper. If the player disconnects before a run, the "retired"
     * callback fires instead and the schedule stops itself.
     */
    public void startTaskForExisting(Player player) {
        if (player.getWorld().getEnvironment() == World.Environment.THE_END) {
            startTaskFor(player);
        }
    }

    private void startTaskFor(Player player) {
        UUID id = player.getUniqueId();
        // Already have a live task for this player (e.g. duplicate/late
        // join+world event ordering) - don't stack a second one.
        if (activeTasks.containsKey(id)) {
            return;
        }

        ScheduledTask task = player.getScheduler().runAtFixedRate(
                plugin,
                t -> tryEndSpawn(player),
                () -> activeTasks.remove(id), // retired callback: player left, drop bookkeeping
                CHECK_INTERVAL_TICKS,
                CHECK_INTERVAL_TICKS
        );
        activeTasks.put(id, task);
    }

    private void stopTaskFor(UUID id) {
        ScheduledTask task = activeTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    private void tryEndSpawn(Player player) {
        PluginSettings settings = plugin.getSettings();
        if (!settings.isEndSpawningEnabled()) {
            debugNotifier.debug(player, "end-spawning disabled in config");
            return;
        }
        if (!player.isOnline() || !player.isValid()) {
            return;
        }

        // This task now only runs while the player is in THE_END (see
        // onPlayerChangedWorld/startTaskFor), so no env check or "not in
        // The End" bookkeeping is needed here anymore. A same-tick dimension
        // change racing this callback is still possible on Folia; re-reading
        // player.getWorld() here (rather than caching it earlier) means a
        // same-tick change is simply reflected in world, same as any other
        // same-tick race in this codebase.
        World world = player.getWorld();

        // Per-tick-cycle random chance, similar in spirit to vanilla's own
        // "attempt every so often, not guaranteed" phantom spawn behavior.
        double roll = random.nextDouble();
        if (roll > settings.getEndSpawnChance()) {
            debugNotifier.debug(player, "chance roll failed (" + roll + " > " + settings.getEndSpawnChance() + ")");
            return;
        }

        int nearby = countNearbyPhantoms(player, settings.getSpawnCheckRadius());
        // Piggybacks on the cap check's own count rather than performing a
        // second nearby-entity scan solely for stats reporting - see
        // PhantomStatsTracker#reportActiveNearby.
        statsTracker.reportActiveNearby(player, nearby);
        if (nearby >= settings.getMaxPhantomsPerPlayer()) {
            debugNotifier.debug(player, "at phantom cap (" + nearby + "/" + settings.getMaxPhantomsPerPlayer() + " within " + settings.getSpawnCheckRadius() + " blocks)");
            return;
        }

        debugNotifier.debug(player, "roll passed (" + roll + " <= " + settings.getEndSpawnChance() + "), nearby=" + nearby + ", attempting to pick location");
        Location spawnLocation = pickSpawnLocation(player, settings);
        if (spawnLocation == null) {
            debugNotifier.debug(player, "pickSpawnLocation returned null (no allowed surface block found in the sampled column)");
            return;
        }
        debugNotifier.debug(player, "valid location found at " + spawnLocation.getBlockX() + "," + spawnLocation.getBlockY() + "," + spawnLocation.getBlockZ() + ", spawning phantom");

        // World#spawn(..., SpawnReason) fires CreatureSpawnEvent with the
        // given reason. We pass NATURAL so PhantomSpawnListener's existing
        // isNaturalSpawnCause(...) check governs this spawn through the same
        // path as a vanilla natural spawn, keeping one source of truth for
        // "is this a valid spawn spot" instead of duplicating the surface
        // check as an authority in two places.
        //
        // The pre-spawn Consumer<Phantom> runs BEFORE CreatureSpawnEvent is
        // fired (it's Paper's hook for configuring an entity pre-add-to-world),
        // so tagging the cause here guarantees PhantomSpawnListener's handler
        // - which runs synchronously inside this same world.spawn() call -
        // always sees the tag already set when it builds its debug report.
        // This is what lets attribution ("caused by which player") live on
        // the single spawn-report call site in PhantomSpawnListener instead
        // of being duplicated here.
        //
        // If PhantomSpawnListener (or any other plugin) cancels the
        // resulting CreatureSpawnEvent, Paper does NOT throw and does NOT
        // reliably return null from every World#spawn overload - the only
        // safe way to detect a vetoed spawn is to check whether the entity
        // Bukkit handed back is still valid/un-removed immediately after.
        Phantom phantom = world.spawn(spawnLocation, Phantom.class,
                CreatureSpawnEvent.SpawnReason.NATURAL,
                p -> spawnCauseTag.tag(p, "End-spawner near player " + player.getName()));

        if (phantom == null || phantom.isDead() || !phantom.isValid()) {
            // PhantomSpawnListener vetoed it (e.g. surface check failed due
            // to a race between our pickSpawnLocation and the world since),
            // or something else removed it immediately. Nothing further to do.
            debugNotifier.debug(player, "world.spawn() call was vetoed (likely by PhantomSpawnListener or another plugin)");
            statsTracker.recordEndSpawnerVetoed();
            return;
        }
        // Successful-spawn debug reporting already happened inside
        // PhantomSpawnListener.onCreatureSpawn (same call stack, via the
        // spawn-cause tag set above) - nothing further to report here.
        statsTracker.recordEndSpawnerSuccess(player);
    }

    /**
     * Number of randomly-offset columns to sample per spawn attempt before
     * giving up. A single sample is fine when the player has open ground on
     * all sides, but on a 1-wide bridge (or any thin structure) over the
     * void, the overwhelming majority of columns within horizontalSpread
     * are void with no highest block at all, so a lone sample almost always
     * misses and pickSpawnLocation returns null on nearly every attempt.
     * Retrying several independent columns in the same call - rather than
     * waiting for the next CHECK_INTERVAL_TICKS tick to try again - makes
     * the odds of finding the (thin) allowed surface within horizontalSpread
     * reasonable without changing the spawn chance/rate semantics at all.
     */
    private static final int LOCATION_SAMPLE_ATTEMPTS = 12;

    /**
     * Picks a location above an actual, known allowed surface near the
     * player, biased upward like vanilla phantom spawns.
     *
     * This deliberately does NOT probe downward from a random high point
     * above the player's current Y. Doing so requires a valid surface block
     * to happen to fall within a short search depth below a point that is
     * itself minHeightAbove-maxHeightAbove blocks above the player - on the
     * End's main island (surface ~Y=64-70, phantom spawn candidates picked
     * 20-30 blocks above that), the real ground is far outside that search
     * window almost every attempt, so it would silently fail almost always.
     * It's also wrong if the player is themselves airborne (elytra, falling,
     * standing on a boat over the void): "above the player" doesn't imply
     * "above solid ground" at all.
     *
     * Instead we find the real ground height under a randomly offset column
     * near the player first (World#getHighestBlockYAt), confirm that block
     * is an allowed surface, and only then place the phantom above that
     * known-good point. Because most columns within horizontalSpread can be
     * void (e.g. a 1-wide bridge), we sample up to LOCATION_SAMPLE_ATTEMPTS
     * independent columns before giving up for this call.
     */
    private Location pickSpawnLocation(Player player, PluginSettings settings) {
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null) {
            return null;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int horizontalSpread = 20;
        int minHeightAbove = 20;

        for (int attempt = 0; attempt < LOCATION_SAMPLE_ATTEMPTS; attempt++) {
            int dx = rnd.nextInt(-horizontalSpread, horizontalSpread + 1);
            int dz = rnd.nextInt(-horizontalSpread, horizontalSpread + 1);
            int x = base.getBlockX() + dx;
            int z = base.getBlockZ() + dz;

            Integer groundY = findAllowedSurfaceY(world, x, z, settings);
            if (groundY == null) {
                continue; // this column was void/disallowed - try another
            }

            int dy = rnd.nextInt(minHeightAbove, MAX_HEIGHT_ABOVE_GROUND + 1);
            int spawnY = Math.min(groundY + dy, world.getMaxHeight() - 1);

            return new Location(world, x + 0.5, spawnY, z + 0.5);
        }

        return null; // no allowed surface found in any sampled column
    }

    /**
     * Finds the Y of the highest non-air block in this column (the real
     * "ground" a player standing here would land on) and returns it only if
     * that block's material is an allowed surface block. Returns null if the
     * column has no blocks (open void) or its surface isn't allowed
     * (e.g. an outer-islands chorus-free column, or bedrock/void terrain).
     *
     * World#getHighestBlockYAt already skips leaves/logs-as-ground the way
     * you'd want for a "top of the world" query, and in The End there's no
     * tree canopy to worry about anyway - the highest block in a column on
     * the main island is end stone, and on outer islands is end stone or a
     * chorus plant.
     */
    private Integer findAllowedSurfaceY(World world, int x, int z, PluginSettings settings) {
        int highestY = world.getHighestBlockYAt(x, z);
        if (highestY <= world.getMinHeight()) {
            return null; // nothing but void in this column
        }

        Block block = world.getBlockAt(x, highestY, z);
        if (!settings.getAllowedSurfaceBlocks().contains(block.getType())) {
            return null;
        }

        return highestY;
    }

    private int countNearbyPhantoms(Player player, double radius) {
        return player.getWorld()
                .getNearbyEntities(player.getLocation(), radius, radius, radius,
                        entity -> entity.getType() == EntityType.PHANTOM)
                .size();
    }
}