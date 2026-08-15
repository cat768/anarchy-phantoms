package com.anarchyphantoms.phantomcontrol;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
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

    public PhantomSpawnListener(AnarchyPhantomsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.PHANTOM) {
            return;
        }

        // Only govern natural/environmental spawn causes. Deliberate spawns
        // (eggs, commands, plugins, custom) are intentionally left untouched.
        if (!isNaturalSpawnCause(event.getSpawnReason())) {
            return;
        }

        PluginSettings settings = plugin.getSettings();
        Location location = event.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        if (settings.isBlockOverworldSpawns() && world.getEnvironment() == World.Environment.NORMAL) {
            event.setCancelled(true);
            return;
        }

        if (settings.isOnlySpawnInEnd() && world.getEnvironment() != World.Environment.THE_END) {
            event.setCancelled(true);
            return;
        }

        if (!isAboveAllowedSurface(location, settings)) {
            event.setCancelled(true);
        }
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
