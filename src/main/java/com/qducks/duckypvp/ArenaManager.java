package com.qducks.duckypvp;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ArenaManager {
    private final DuckyPVP plugin;
    private final KitManager kitManager;
    private final Map<BlockKey, BlockData> originalBlocks = new HashMap<>();
    private final Set<UUID> playersInside = new HashSet<>();
    private final Set<EntityType> temporaryEntityTypes = new HashSet<>();
    private final Set<String> excludedRegionNames = new HashSet<>();

    private String worldName;
    private String regionName;
    private long resetIntervalTicks;
    private long nextResetAtMillis;
    private BukkitTask resetTask;
    private boolean resetting;

    public ArenaManager(DuckyPVP plugin, KitManager kitManager) {
        this.plugin = plugin;
        this.kitManager = kitManager;
        loadSettings();
    }

    public void start() {
        stop();
        long interval = Math.max(20L, resetIntervalTicks);
        nextResetAtMillis = System.currentTimeMillis() + interval * 50L;
        resetTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> resetArena(true), interval, interval);
    }

    public void stop() {
        if (resetTask != null) {
            resetTask.cancel();
            resetTask = null;
        }
    }

    public void reload() {
        stop();
        loadSettings();
        start();
    }

    private void loadSettings() {
        worldName = plugin.getConfig().getString("arena.world", "world");
        regionName = plugin.getConfig().getString("arena.region", "sandpvp").toLowerCase(Locale.ROOT);
        long seconds = Math.max(1L, plugin.getConfig().getLong("arena.reset-interval-seconds", 900L));
        resetIntervalTicks = seconds * 20L;

        excludedRegionNames.clear();
        for (String excluded : plugin.getConfig().getStringList("arena.excluded-regions")) {
            if (!excluded.isBlank()) {
                excludedRegionNames.add(excluded.toLowerCase(Locale.ROOT));
            }
        }

        temporaryEntityTypes.clear();
        for (String raw : plugin.getConfig().getStringList("reset.temporary-entities")) {
            try {
                temporaryEntityTypes.add(EntityType.valueOf(raw.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown temporary entity type in config.yml: " + raw);
            }
        }
    }

    public boolean isInArena(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().getName().equalsIgnoreCase(worldName)) {
            return false;
        }

        RegionManager manager = WorldGuard.getInstance()
                .getPlatform()
                .getRegionContainer()
                .get(BukkitAdapter.adapt(location.getWorld()));
        if (manager == null) {
            return false;
        }

        BlockVector3 point = BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        ProtectedRegion arenaRegion = manager.getRegion(regionName);
        if (arenaRegion == null || !arenaRegion.contains(point)) {
            return false;
        }

        for (String excludedName : excludedRegionNames) {
            ProtectedRegion excluded = manager.getRegion(excludedName);
            if (excluded != null && excluded.contains(point)) {
                return false;
            }
        }
        return true;
    }

    public void syncPlayer(Player player) {
        boolean nowInside = isInArena(player.getLocation());
        boolean wasInside = playersInside.contains(player.getUniqueId());

        if (nowInside && !wasInside) {
            playersInside.add(player.getUniqueId());
            kitManager.enterArena(player);
            sendEnterMessage(player);
        } else if (!nowInside && wasInside) {
            playersInside.remove(player.getUniqueId());
            kitManager.leaveArena(player);
        } else if (!nowInside && kitManager.hasBackup(player.getUniqueId())) {
            kitManager.restoreStaleBackup(player);
        }
    }

    public void handlePositionChange(Player player, Location destination) {
        if (destination == null) {
            return;
        }

        boolean nowInside = isInArena(destination);
        boolean wasInside = playersInside.contains(player.getUniqueId());
        if (nowInside == wasInside) {
            return;
        }

        if (nowInside) {
            playersInside.add(player.getUniqueId());
            kitManager.enterArena(player);
            sendEnterMessage(player);
        } else {
            playersInside.remove(player.getUniqueId());
            kitManager.leaveArena(player);
        }
    }

    public void handleQuit(Player player) {
        playersInside.remove(player.getUniqueId());
        if (kitManager.hasBackup(player.getUniqueId())) {
            kitManager.leaveArena(player);
        }
    }

    public boolean isTrackedInside(Player player) {
        return playersInside.contains(player.getUniqueId());
    }

    public void recordOriginal(Block block, BlockData originalData) {
        if (resetting || block == null || originalData == null || !isInArena(block.getLocation())) {
            return;
        }
        BlockKey key = new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        originalBlocks.putIfAbsent(key, originalData.clone());
    }

    public void resetArena(boolean rerollKit) {
        resetting = true;
        try {
            for (Map.Entry<BlockKey, BlockData> entry : originalBlocks.entrySet()) {
                Block block = entry.getKey().resolve();
                if (block != null) {
                    block.setBlockData(entry.getValue(), false);
                }
            }
            originalBlocks.clear();

            if (plugin.getConfig().getBoolean("reset.remove-temporary-entities", true)) {
                removeTemporaryEntities();
            }

            if (rerollKit) {
                kitManager.rollNextKit();
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (isInArena(player.getLocation())) {
                    playersInside.add(player.getUniqueId());
                    kitManager.rekit(player);
                } else if (playersInside.remove(player.getUniqueId())) {
                    kitManager.leaveArena(player);
                }
            }

            nextResetAtMillis = System.currentTimeMillis() + resetIntervalTicks * 50L;
            if (plugin.getConfig().getBoolean("arena.broadcast-reset", true)) {
                String message = plugin.getConfig().getString(
                        "arena.reset-message",
                        "&6&lDUCKY PVP &8» &eArena reset! New kit: &f%kit%"
                );
                Bukkit.broadcastMessage(color(message.replace("%kit%", kitManager.getActiveDisplayName())));
            }
        } finally {
            resetting = false;
        }
    }

    public void rerollKit() {
        kitManager.rollNextKit();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isInArena(player.getLocation())) {
                playersInside.add(player.getUniqueId());
                kitManager.rekit(player);
            }
        }
    }

    private void removeTemporaryEntities() {
        World world = Bukkit.getWorld(worldName);
        if (world == null || temporaryEntityTypes.isEmpty()) {
            return;
        }

        for (Entity entity : world.getEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            if (temporaryEntityTypes.contains(entity.getType()) && isInArena(entity.getLocation())) {
                entity.remove();
            }
        }
    }

    private void sendEnterMessage(Player player) {
        String message = plugin.getConfig().getString(
                "arena.enter-message",
                "&6&lDUCKY PVP &8» &7Current kit: &f%kit%"
        );
        player.sendMessage(color(message.replace("%kit%", kitManager.getActiveDisplayName())));
    }

    public String getRegionName() {
        return regionName;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getChangedBlockCount() {
        return originalBlocks.size();
    }

    public long getSecondsUntilReset() {
        return Math.max(0L, (nextResetAtMillis - System.currentTimeMillis() + 999L) / 1000L);
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    private record BlockKey(String world, int x, int y, int z) {
        private Block resolve() {
            World bukkitWorld = Bukkit.getWorld(world);
            return bukkitWorld == null ? null : bukkitWorld.getBlockAt(x, y, z);
        }
    }
}
