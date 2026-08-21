package com.anarchyphantoms.phantomcontrol;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Phantom;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Restricts where phantoms are allowed to spawn:
 *  - Never in the overworld.
 *  - Only in The End.
 *  - Only directly above end stone, chorus plant, or chorus flower blocks.
 *
 * Natural spawn causes are restricted; spawns explicitly requested by other
 * plugins/commands (SPAWNER_EGG, COMMAND, CUSTOM, etc.) are left alone so
 * admins/other systems retain control over deliberate spawns.
 */
public final class PhantomSpawnListener implements Listener {

    private final AnarchyPhantomsPlugin plugin;
    private final PhantomDebugNotifier debugNotifier;
    private final PhantomSpawnCauseTag spawnCauseTag;

    public PhantomSpawnListener(AnarchyPhantomsPlugin plugin) {
        this.plugin = plugin;
        this.debugNotifier = plugin.getDebugNotifier();
        this.spawnCauseTag = plugin.getSpawnCauseTag();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.PHANTOM) {
            return;
        }

        // Only govern natural/environmental spawn causes. Deliberate spawns
        // (eggs, commands, plugins, custom) are intentionally left untouched
        // here, but still reported below so debug output covers every
        // phantom that actually ends up in the world, not just the ones
        // this listener had a say in.
        if (!isNaturalSpawnCause(event.getSpawnReason())) {
            debugNotifier.spawn(event.getLocation(), causeLabel(event));
            return;
        }

        PluginSettings settings = plugin.getSettings();
        Location location = event.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        if (settings.isBlockOverworldSpawns() && world.getEnvironment() == World.Environment.NORMAL) {
            debugNotifier.debug("blocked phantom spawn at " + coords(location) + " in " + world.getName()
                    + " | cause: " + causeLabel(event) + " | reason: overworld spawns disabled");
            event.setCancelled(true);
            return;
        }

        if (settings.isOnlySpawnInEnd() && world.getEnvironment() != World.Environment.THE_END) {
            debugNotifier.debug("blocked phantom spawn at " + coords(location) + " in " + world.getName()
                    + " | cause: " + causeLabel(event) + " | reason: only-spawn-in-end is enabled and this world isn't The End");
            event.setCancelled(true);
            return;
        }

        if (!isAboveAllowedSurface(location, settings)) {
            debugNotifier.debug("blocked phantom spawn at " + coords(location) + " in " + world.getName()
                    + " | cause: " + causeLabel(event) + " | reason: not above an allowed surface block");
            event.setCancelled(true);
            return;
        }

        // Passed every check this listener governs: this is the single
        // place a successful phantom spawn gets reported, for both vanilla-
        // routed natural spawns AND PhantomEndSpawner's active spawns (which
        // also go through SpawnReason.NATURAL so this same veto path governs
        // them - see PhantomEndSpawner). causeLabel(...) tells them apart.
        debugNotifier.spawn(location, causeLabel(event));
    }

    /**
     * Builds the "cause" portion of a debug report. If PhantomEndSpawner
     * tagged this entity before it was added to the world, that tag - which
     * names the specific player whose presence triggered the spawn - takes
     * priority over the generic Bukkit spawn reason, since it's strictly
     * more specific and is exactly the "caused by which player" detail
     * debug output is meant to surface. Falls back to the raw SpawnReason
     * for every other case (vanilla natural spawns, eggs, commands, etc.).
     */
    private String causeLabel(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Phantom phantom) {
            String tagged = spawnCauseTag.read(phantom);
            if (tagged != null) {
                return tagged;
            }
        }
        return "spawn reason " + event.getSpawnReason();
    }

    private static String coords(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private boolean isNaturalSpawnCause(CreatureSpawnEvent.SpawnReason reason) {
        switch (reason) {
            case NATURAL:
            case DEFAULT:
            case REINFORCEMENTS:
                return true;
            default:
                return false;
        }
    }

    /**
     * Walks downward from the spawn location, up to the configured depth,
     * looking for the first non-air block and checking whether it's an
     * allowed surface material.
     */
    private boolean isAboveAllowedSurface(Location spawnLocation, PluginSettings settings) {
        World world = spawnLocation.getWorld();
        if (world == null) {
            return false;
        }

        int startY = spawnLocation.getBlockY();
        int minY = world.getMinHeight();
        int depth = settings.getSurfaceCheckDepth();

        for (int i = 1; i <= depth; i++) {
            int y = startY - i;
            if (y < minY) {
                break;
            }
            Block block = world.getBlockAt(spawnLocation.getBlockX(), y, spawnLocation.getBlockZ());
            Material type = block.getType();
            if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
                continue;
            }
            return settings.getAllowedSurfaceBlocks().contains(type);
        }
        return false;
    }
}