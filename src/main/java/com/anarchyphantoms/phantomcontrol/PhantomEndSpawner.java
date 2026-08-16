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
            debug(player, "pickSpawnLocation returned null (no valid surface below candidate point)");
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
     * Picks a location above the player, biased upward like vanilla phantom
     * spawns, and directly validates it sits above an allowed surface within
     * the configured search depth - the same rule PhantomSpawnListener
     * enforces, checked here first so we don't waste a spawn attempt (and
     * the associated event overhead) on an obviously bad spot.
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
        int maxHeightAbove = 30;

        int dx = rnd.nextInt(-horizontalSpread, horizontalSpread + 1);
        int dz = rnd.nextInt(-horizontalSpread, horizontalSpread + 1);
        int dy = rnd.nextInt(minHeightAbove, maxHeightAbove + 1);

        Location candidate = base.clone().add(dx, dy, dz);
        candidate.setY(Math.min(candidate.getY(), world.getMaxHeight() - 1));

        if (!hasValidSurfaceBelow(candidate, world, settings)) {
            return null;
        }

        return candidate;
    }

    /**
     * Same walk-downward logic as PhantomSpawnListener#isAboveAllowedSurface,
     * duplicated intentionally: this is a cheap pre-check to avoid pointless
     * spawn attempts, while PhantomSpawnListener remains the authoritative
     * gate that actually allows/cancels the resulting CreatureSpawnEvent.
     */
    private boolean hasValidSurfaceBelow(Location location, World world, PluginSettings settings) {
        int startY = location.getBlockY();
        int minY = world.getMinHeight();
        int depth = settings.getSurfaceCheckDepth();

        for (int i = 1; i <= depth; i++) {
            int y = startY - i;
            if (y < minY) {
                break;
            }
            Block block = world.getBlockAt(location.getBlockX(), y, location.getBlockZ());
            Material type = block.getType();
            if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
                continue;
            }
            return settings.getAllowedSurfaceBlocks().contains(type);
        }
        return false;
    }

    private int countNearbyPhantoms(Player player, double radius) {
        return player.getWorld()
                .getNearbyEntities(player.getLocation(), radius, radius, radius,
                        entity -> entity.getType() == EntityType.PHANTOM)
                .size();
    }
}