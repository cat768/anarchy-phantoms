package com.anarchyphantoms.phantomcontrol;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Random;
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
    private final Random random = new Random();

    public PhantomEndSpawner(AnarchyPhantomsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        startTaskFor(event.getPlayer());
    }

    /**
     * Starts this player's own repeating eligibility/spawn check. Using
     * Player#getScheduler() means the callback only ever runs on the thread
     * that currently owns this specific player, following them across
     * regions on Folia, and behaves like a normal repeating task on
     * standard Paper. If the player disconnects before a run, the "retired"
     * callback fires instead and the schedule stops itself - no manual
     * cancel bookkeeping required.
     */
    public void startTaskForExisting(Player player) {
        startTaskFor(player);
    }

    private void startTaskFor(Player player) {
        player.getScheduler().runAtFixedRate(
                plugin,
                task -> tryEndSpawn(player),
                null, // retired callback: player already left, nothing to clean up
                CHECK_INTERVAL_TICKS,
                CHECK_INTERVAL_TICKS
        );
    }

    private void tryEndSpawn(Player player) {
        PluginSettings settings = plugin.getSettings();
        if (!settings.isEndSpawningEnabled()) {
            debug(player, "end-spawning disabled in config");
            return;
        }
        if (!player.isOnline() || !player.isValid()) {
            return;
        }

        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            debug(player, "not in The End (env=" + world.getEnvironment() + ")");
            return;
        }

        // Per-tick-cycle random chance, similar in spirit to vanilla's own
        // "attempt every so often, not guaranteed" phantom spawn behavior.
        double roll = random.nextDouble();
        if (roll > settings.getEndSpawnChance()) {
            debug(player, "chance roll failed (" + roll + " > " + settings.getEndSpawnChance() + ")");
            return;
        }

        int nearby = countNearbyPhantoms(player, settings.getSpawnCheckRadius());
        if (nearby >= settings.getMaxPhantomsPerPlayer()) {
            debug(player, "at phantom cap (" + nearby + "/" + settings.getMaxPhantomsPerPlayer() + " within " + settings.getSpawnCheckRadius() + " blocks)");
            return;
        }

        debug(player, "roll passed (" + roll + " <= " + settings.getEndSpawnChance() + "), nearby=" + nearby + ", attempting to pick location");
        Location spawnLocation = pickSpawnLocation(player, settings);
        if (spawnLocation == null) {
            debug(player, "pickSpawnLocation returned null (no allowed surface block found in the sampled column)");
            return;
        }
        debug(player, "valid location found at " + spawnLocation.getBlockX() + "," + spawnLocation.getBlockY() + "," + spawnLocation.getBlockZ() + ", spawning phantom");

        // World#spawn(..., SpawnReason) fires CreatureSpawnEvent with the
        // given reason. We pass NATURAL so PhantomSpawnListener's existing
        // isNaturalSpawnCause(...) check governs this spawn through the same
        // path as a vanilla natural spawn, keeping one source of truth for
        // "is this a valid spawn spot" instead of duplicating the surface
        // check as an authority in two places.
        //
        // If PhantomSpawnListener (or any other plugin) cancels the
        // resulting CreatureSpawnEvent, Paper does NOT throw and does NOT
        // reliably return null from every World#spawn overload - the only
        // safe way to detect a vetoed spawn is to check whether the entity
        // Bukkit handed back is still valid/un-removed immediately after.
        Phantom phantom = world.spawn(spawnLocation, Phantom.class,
                CreatureSpawnEvent.SpawnReason.NATURAL);

        if (phantom == null || phantom.isDead() || !phantom.isValid()) {
            // PhantomSpawnListener vetoed it (e.g. surface check failed due
            // to a race between our pickSpawnLocation and the world since),
            // or something else removed it immediately. Nothing further to do.
            debug(player, "world.spawn() call was vetoed (likely by PhantomSpawnListener or another plugin)");
            return;
        }
        debug(player, "phantom spawn SUCCEEDED at " + spawnLocation.getBlockX() + "," + spawnLocation.getBlockY() + "," + spawnLocation.getBlockZ());
    }

    /**
     * Logs a debug line to console only if debug mode is currently on
     * (config default, or runtime-toggled via /ap debug). Never sent to
     * players in chat - console/log-file only, regardless of who is op.
     */
    private void debug(Player player, String message) {
        if (!plugin.getSettings().isDebugEnabled()) {
            return;
        }
        plugin.getLogger().info("[AP-DEBUG] " + player.getName() + ": " + message);
    }

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
     * known-good point.
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

        int dx = rnd.nextInt(-horizontalSpread, horizontalSpread + 1);
        int dz = rnd.nextInt(-horizontalSpread, horizontalSpread + 1);
        int x = base.getBlockX() + dx;
        int z = base.getBlockZ() + dz;

        Integer groundY = findAllowedSurfaceY(world, x, z, settings);
        if (groundY == null) {
            return null;
        }

        int dy = rnd.nextInt(minHeightAbove, MAX_HEIGHT_ABOVE_GROUND + 1);
        int spawnY = Math.min(groundY + dy, world.getMaxHeight() - 1);

        return new Location(world, x + 0.5, spawnY, z + 0.5);
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