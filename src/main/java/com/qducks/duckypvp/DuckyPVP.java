package com.qducks.duckypvp;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class DuckyPVP extends JavaPlugin {
    private KitManager kitManager;
    private VoteManager voteManager;
    private ArenaManager arenaManager;
    private CombatManager combatManager;
    private KitItemGuard kitItemGuard;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("kits.yml", false);

        try {
            kitManager = new KitManager(this);
            voteManager = new VoteManager(kitManager);
            arenaManager = new ArenaManager(this, kitManager, voteManager);
            combatManager = new CombatManager(this, arenaManager);
            kitItemGuard = new KitItemGuard(this, arenaManager, kitManager);
        } catch (Exception ex) {
            getLogger().severe("DuckyPVP could not start: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new ArenaListener(this, arenaManager, kitManager), this);
        getServer().getPluginManager().registerEvents(new CombatCommandListener(this, arenaManager, combatManager), this);
        getServer().getPluginManager().registerEvents(kitItemGuard, this);

        VoteKitMenu voteMenu = new VoteKitMenu(this, kitManager, voteManager);
        getServer().getPluginManager().registerEvents(voteMenu, this);

        PluginCommand adminCommand = getCommand("duckypvp");
        if (adminCommand != null) {
            DuckyPvpCommand executor = new DuckyPvpCommand(this, arenaManager, kitManager, voteManager);
            adminCommand.setExecutor(executor);
            adminCommand.setTabCompleter(executor);
        }

        PluginCommand voteCommand = getCommand("votekit");
        if (voteCommand != null) {
            voteCommand.setExecutor(voteMenu);
        }

        arenaManager.start();
        combatManager.start();
        getServer().getScheduler().runTask(this, () ->
                getServer().getOnlinePlayers().forEach(arenaManager::syncPlayer)
        );

        getLogger().info("DuckyPVP enabled. Arena region: "
                + arenaManager.getWorldName() + ":" + arenaManager.getRegionName());
    }

    @Override
    public void onDisable() {
        if (combatManager != null) {
            combatManager.stop();
        }
        if (arenaManager != null) {
            arenaManager.stop();
        }
        if (kitManager != null) {
            kitManager.restoreAllOnline();
        }
    }

    /**
     * Integration hook for trusted moderation systems that intentionally wipe
     * a player's saved gameplay state after first taking their own recovery snapshot.
     */
    public String exportPlayerBackup(UUID uuid) {
        return kitManager == null ? "" : kitManager.exportBackup(uuid);
    }

    public boolean discardPlayerBackup(UUID uuid) {
        return kitManager != null && kitManager.discardBackup(uuid);
    }

    public boolean hasPlayerBackup(UUID uuid) {
        return kitManager != null && kitManager.hasBackup(uuid);
    }

    /**
     * Integration hook for profile switchers (SDS Abuse Mode): gives the player back the inventory
     * saved when they entered the arena, removing the kit, so the switcher captures real items.
     * Returns true if the player no longer holds a kit backup.
     */
    public boolean releaseArenaKit(Player player) {
        if (kitManager == null || !kitManager.hasBackup(player.getUniqueId())) {
            return true;
        }
        kitManager.leaveArena(player);
        return !kitManager.hasBackup(player.getUniqueId());
    }

    /**
     * Integration hook: true while DuckyPVP's arena combat lock (and its action bar) is active, so
     * other combat plugins can avoid overwriting that action bar.
     */
    public boolean isArenaCombatLocked(Player player) {
        return combatManager != null && combatManager.isLocked(player);
    }

    /** Integration hook: re-applies the arena kit if the player is inside the arena without one. */
    public void syncArenaKit(Player player) {
        if (arenaManager != null) {
            arenaManager.syncKit(player);
        }
    }

    public void reloadDuckyPvp() {
        reloadConfig();
        kitManager.reload();
        voteManager.sanitizeAfterReload();
        arenaManager.reload();
        combatManager.reload();
        kitItemGuard.reload();
    }
}
