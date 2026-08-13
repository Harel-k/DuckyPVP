package com.qducks.duckypvp;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

public final class KitManager {
    private final DuckyPVP plugin;
    private final File kitsFile;
    private final File backupsFile;
    private final Random random = new Random();

    private YamlConfiguration kits;
    private YamlConfiguration backups;
    private String activeKit;

    public KitManager(DuckyPVP plugin) {
        this.plugin = plugin;
        this.kitsFile = new File(plugin.getDataFolder(), "kits.yml");
        this.backupsFile = new File(plugin.getDataFolder(), "player-backups.yml");
        reload();
        this.backups = YamlConfiguration.loadConfiguration(backupsFile);
        if (activeKit == null) {
            rollNextKit();
        }
    }

    public void reload() {
        this.kits = YamlConfiguration.loadConfiguration(kitsFile);
        if (activeKit != null && !isValidKit(activeKit)) {
            activeKit = null;
        }
        if (activeKit == null && !getKitIds().isEmpty()) {
            rollNextKit();
        }
    }

    public String rollNextKit() {
        activeKit = pickRandomKitIdDifferentFromActive();
        return activeKit;
    }

    public String pickRandomKitIdDifferentFromActive() {
        List<String> ids = getKitIds();
        if (ids.isEmpty()) {
            throw new IllegalStateException("No kits are configured in kits.yml");
        }
        if (ids.size() == 1) {
            return ids.getFirst();
        }

        String next;
        do {
            next = ids.get(random.nextInt(ids.size()));
        } while (next.equalsIgnoreCase(activeKit));
        return next;
    }

    public boolean activateKit(String kitId) {
        if (!isValidKit(kitId)) {
            return false;
        }
        activeKit = kitId.toLowerCase(Locale.ROOT);
        return true;
    }

    public boolean isValidKit(String kitId) {
        if (kitId == null) {
            return false;
        }
        return kits.isConfigurationSection("kits." + kitId.toLowerCase(Locale.ROOT));
    }

    public List<String> getKitIds() {
        ConfigurationSection root = kits.getConfigurationSection("kits");
        if (root == null) {
            return List.of();
        }
        return new ArrayList<>(root.getKeys(false));
    }

    public String getActiveKitId() {
        return activeKit;
    }

    public String getActiveDisplayName() {
        return getDisplayName(activeKit);
    }

    public String getDisplayName(String kitId) {
        if (kitId == null || !isValidKit(kitId)) {
            return color("&7None");
        }
        return color(kits.getString("kits." + kitId + ".display-name", kitId));
    }

    public Material getIconMaterial(String kitId) {
        if (!isValidKit(kitId)) {
            return Material.BARRIER;
        }
        Material material = Material.matchMaterial(kits.getString("kits." + kitId + ".icon", "CHEST"));
        return material == null || material.isAir() ? Material.CHEST : material;
    }

    public List<String> getDescription(String kitId) {
        if (!isValidKit(kitId)) {
            return List.of();
        }
        return kits.getStringList("kits." + kitId + ".description");
    }

    public boolean hasBackup(UUID uuid) {
        return backups.contains("players." + uuid);
    }

    public void enterArena(Player player) {
        if (!hasBackup(player.getUniqueId())) {
            saveBackup(player);
        }
        applyActiveKit(player);
    }

    public void rekit(Player player) {
        if (!hasBackup(player.getUniqueId())) {
            saveBackup(player);
        }
        applyActiveKit(player);
    }

    public void leaveArena(Player player) {
        restoreBackup(player);
    }

    public void restoreStaleBackup(Player player) {
        if (hasBackup(player.getUniqueId())) {
            restoreBackup(player);
        }
    }

    public void restoreAllOnline() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (hasBackup(player.getUniqueId())) {
                restoreBackup(player);
            }
        }
    }

    private void applyActiveKit(Player player) {
        if (activeKit == null) {
            rollNextKit();
        }

        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(null);

        ConfigurationSection items = kits.getConfigurationSection("kits." + activeKit + ".items");
        if (items != null) {
            for (String itemId : items.getKeys(false)) {
                ConfigurationSection section = items.getConfigurationSection(itemId);
                if (section == null) {
                    continue;
                }

                ItemStack item = buildItem(section);
                if (item == null) {
                    plugin.getLogger().warning("Invalid item '" + itemId + "' in kit '" + activeKit + "'.");
                    continue;
                }
                putInSlot(inventory, section.getString("slot", "0"), item);
            }
        }

        if (plugin.getConfig().getBoolean("kits.heal-on-enter", true)) {
            @SuppressWarnings("deprecation") double maxHealth = player.getMaxHealth();
            player.setHealth(maxHealth);
        }
        if (plugin.getConfig().getBoolean("kits.feed-on-enter", true)) {
            player.setFoodLevel(20);
            player.setSaturation(20.0f);
        }
        player.updateInventory();
    }

    private ItemStack buildItem(ConfigurationSection section) {
        Material material = Material.matchMaterial(section.getString("material", ""));
        if (material == null || material.isAir()) {
            return null;
        }

        int amount = Math.max(1, Math.min(material.getMaxStackSize(), section.getInt("amount", 1)));
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();

        if (meta != null && section.isString("name")) {
            meta.setDisplayName(color(section.getString("name", "")));
            item.setItemMeta(meta);
        }

        ConfigurationSection enchantments = section.getConfigurationSection("enchantments");
        if (enchantments != null) {
            for (String enchantmentName : enchantments.getKeys(false)) {
                Enchantment enchantment = Enchantment.getByKey(
                        NamespacedKey.minecraft(enchantmentName.toLowerCase(Locale.ROOT))
                );
                if (enchantment == null) {
                    plugin.getLogger().warning("Unknown enchantment '" + enchantmentName + "'.");
                    continue;
                }
                item.addUnsafeEnchantment(enchantment, enchantments.getInt(enchantmentName));
            }
        }
        return item;
    }

    private void putInSlot(PlayerInventory inventory, String rawSlot, ItemStack item) {
        String slot = rawSlot.toLowerCase(Locale.ROOT);
        switch (slot) {
            case "helmet" -> inventory.setHelmet(item);
            case "chestplate" -> inventory.setChestplate(item);
            case "leggings" -> inventory.setLeggings(item);
            case "boots" -> inventory.setBoots(item);
            case "offhand" -> inventory.setItemInOffHand(item);
            default -> {
                try {
                    int index = Integer.parseInt(slot);
                    if (index >= 0 && index < 36) {
                        inventory.setItem(index, item);
                    } else {
                        inventory.addItem(item);
                    }
                } catch (NumberFormatException ex) {
                    inventory.addItem(item);
                }
            }
        }
    }

    private void saveBackup(Player player) {
        String root = "players." + player.getUniqueId();
        PlayerInventory inventory = player.getInventory();

        backups.set(root + ".name", player.getName());
        backups.set(root + ".health", player.getHealth());
        backups.set(root + ".food", player.getFoodLevel());
        backups.set(root + ".saturation", player.getSaturation());

        for (int i = 0; i < inventory.getSize(); i++) {
            backups.set(root + ".contents." + i, inventory.getItem(i));
        }
        saveBackups();
    }

    private void restoreBackup(Player player) {
        UUID uuid = player.getUniqueId();
        String root = "players." + uuid;
        if (!backups.contains(root)) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(null);

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, backups.getItemStack(root + ".contents." + i));
        }

        double savedHealth = backups.getDouble(root + ".health", 20.0);
        @SuppressWarnings("deprecation") double maxHealth = player.getMaxHealth();
        player.setHealth(Math.max(0.5, Math.min(maxHealth, savedHealth)));
        player.setFoodLevel(Math.max(0, Math.min(20, backups.getInt(root + ".food", 20))));
        player.setSaturation((float) Math.max(0.0, Math.min(20.0, backups.getDouble(root + ".saturation", 5.0))));

        backups.set(root, null);
        saveBackups();
        player.updateInventory();
    }

    private void saveBackups() {
        try {
            backups.save(backupsFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save player-backups.yml: " + ex.getMessage());
        }
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }
}
