package com.qducks.duckypvp;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DuckyPVP extends JavaPlugin {
    private KitManager kitManager;
    private ArenaManager arenaManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("kits.yml", false);

        try {
            kitManager = new KitManager(this);
            arenaManager = new ArenaManager(this, kitManager);
        } catch (Exception ex) {
            getLogger().severe("DuckyPVP could not start: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(
                new ArenaListener(this, arenaManager, kitManager),
                this
        );

        PluginCommand command = getCommand("duckypvp");
        if (command != null) {
            DuckyPvpCommand executor = new DuckyPvpCommand(this, arenaManager, kitManager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        arenaManager.start();
        getServer().getScheduler().runTask(this, () ->
                getServer().getOnlinePlayers().forEach(arenaManager::syncPlayer)
        );

        getLogger().info("DuckyPVP enabled. Arena region: "
                + arenaManager.getWorldName() + ":" + arenaManager.getRegionName());
    }

    @Override
    public void onDisable() {
        if (arenaManager != null) {
            arenaManager.stop();
        }
        if (kitManager != null) {
            kitManager.restoreAllOnline();
        }
    }

    public void reloadDuckyPvp() {
        reloadConfig();
        kitManager.reload();
        arenaManager.reload();
    }
}
