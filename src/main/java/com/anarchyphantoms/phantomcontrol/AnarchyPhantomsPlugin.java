package com.anarchyphantoms.phantomcontrol;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * AnarchyPhantoms - Phantom Control
 *
 * Restricts Phantom spawning to The End dimension only, and only directly
 * above end stone or chorus plant/chorus flower blocks. Phantoms remain
 * passive (non-targeting, silent) until a player actually attacks one,
 * at which point that specific phantom becomes hostile and vocal.
 */
public final class AnarchyPhantomsPlugin extends JavaPlugin {

    private PluginSettings settings;
    private PhantomProvocationTracker provocationTracker;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.settings = new PluginSettings(this);
        this.provocationTracker = new PhantomProvocationTracker(this);

        PhantomSpawnListener spawnListener = new PhantomSpawnListener(this);
        PhantomBehaviorListener behaviorListener = new PhantomBehaviorListener(this, provocationTracker);
        PhantomSoundListener soundListener = new PhantomSoundListener(this, provocationTracker);

        getServer().getPluginManager().registerEvents(spawnListener, this);
        getServer().getPluginManager().registerEvents(behaviorListener, this);
        getServer().getPluginManager().registerEvents(soundListener, this);

        getLogger().info("AnarchyPhantoms phantom control enabled: End-only spawns, endstone/chorus surface required, passive until attacked.");
    }

    @Override
    public void onDisable() {
        getLogger().info("AnarchyPhantoms phantom control disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("anarchyphantoms")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("anarchyphantoms.admin")) {
                    sender.sendMessage("You do not have permission to do that.");
                    return true;
                }
                reloadConfig();
                settings.reload();
                sender.sendMessage("[AnarchyPhantoms] Configuration reloaded.");
                return true;
            }
            sender.sendMessage("[AnarchyPhantoms] Usage: /anarchyphantoms reload");
            return true;
        }
        return false;
    }

    public PluginSettings getSettings() {
        return settings;
    }

    public PhantomProvocationTracker getProvocationTracker() {
        return provocationTracker;
    }
}