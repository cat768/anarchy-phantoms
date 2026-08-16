package com.anarchyphantoms.phantomcontrol;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Typed accessor over config.yml values, re-read on demand so /anarchyphantoms reload works.
 */
public final class PluginSettings {

    private final AnarchyPhantomsPlugin plugin;

    private boolean blockOverworldSpawns;
    private boolean onlySpawnInEnd;
    private Set<Material> allowedSurfaceBlocks;
    private int surfaceCheckDepth;
    private boolean passiveUntilAttacked;
    private boolean silenceScreechUntilAttacked;
    private long provokedDurationTicks;
    private boolean endSpawningEnabled;
    private double endSpawnChance;
    private double spawnCheckRadius;
    private int maxPhantomsPerPlayer;

    public PluginSettings(AnarchyPhantomsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration config = plugin.getConfig();
        Logger logger = plugin.getLogger();

        this.blockOverworldSpawns = config.getBoolean("phantoms.block-overworld-spawns", true);
        this.onlySpawnInEnd = config.getBoolean("phantoms.only-spawn-in-end", true);
        this.surfaceCheckDepth = Math.max(1, config.getInt("phantoms.surface-check-depth", 5));
        this.passiveUntilAttacked = config.getBoolean("phantoms.passive-until-attacked", true);
        this.silenceScreechUntilAttacked = config.getBoolean("phantoms.silence-screech-until-attacked", true);
        this.provokedDurationTicks = config.getLong("phantoms.provoked-duration-ticks", 6000);
        this.endSpawningEnabled = config.getBoolean("phantoms.end-spawning.enabled", true);
        this.endSpawnChance = clamp01(config.getDouble("phantoms.end-spawning.spawn-chance", 0.15));
        this.spawnCheckRadius = Math.max(1.0, config.getDouble("phantoms.end-spawning.spawn-check-radius", 32.0));
        this.maxPhantomsPerPlayer = Math.max(0, config.getInt("phantoms.end-spawning.max-phantoms-per-player", 4));

        List<String> materialNames = config.getStringList("phantoms.allowed-surface-blocks");
        EnumSet<Material> materials = EnumSet.noneOf(Material.class);
        if (materialNames.isEmpty()) {
            materials.add(Material.END_STONE);
            materials.add(Material.CHORUS_PLANT);
            materials.add(Material.CHORUS_FLOWER);
        } else {
            for (String name : materialNames) {
                Material material = Material.matchMaterial(name);
                if (material != null) {
                    materials.add(material);
                } else {
                    logger.warning("[AnarchyPhantoms] Unknown material in allowed-surface-blocks: " + name);
                }
            }
        }
        this.allowedSurfaceBlocks = materials;
    }

    public boolean isBlockOverworldSpawns() {
        return blockOverworldSpawns;
    }

    public boolean isOnlySpawnInEnd() {
        return onlySpawnInEnd;
    }

    public Set<Material> getAllowedSurfaceBlocks() {
        return allowedSurfaceBlocks;
    }

    public int getSurfaceCheckDepth() {
        return surfaceCheckDepth;
    }

    public boolean isPassiveUntilAttacked() {
        return passiveUntilAttacked;
    }

    public boolean isSilenceScreechUntilAttacked() {
        return silenceScreechUntilAttacked;
    }

    public long getProvokedDurationTicks() {
        return provokedDurationTicks;
    }

    public boolean isEndSpawningEnabled() {
        return endSpawningEnabled;
    }

    public double getEndSpawnChance() {
        return endSpawnChance;
    }

    public double getSpawnCheckRadius() {
        return spawnCheckRadius;
    }

    public int getMaxPhantomsPerPlayer() {
        return maxPhantomsPerPlayer;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
