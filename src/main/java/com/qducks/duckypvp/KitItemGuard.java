package com.qducks.duckypvp;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps arena kit items inside the arena.
 *
 * Kit items carry a persistent marker (see {@link KitManager#isKitItem(ItemStack)}).
 * Every rule here only acts on marked items or on players who currently hold an
 * arena kit, so normal gameplay outside the arena is unaffected.
 */
public final class KitItemGuard implements Listener {
    private static final List<String> DEFAULT_BLOCKED_COMMANDS = List.of(
            "sell", "sellall", "ah", "auctionhouse", "order", "orders", "shop", "trade",
            "ec", "enderchest", "echest", "craft", "workbench", "wb", "anvil",
            "pv", "playervault", "playervaults", "trash", "disposal", "crates",
            "abuse", "adminmode"
    );

    private final DuckyPVP plugin;
    private final ArenaManager arena;
    private final KitManager kits;
    private final Set<String> blockedCommands = new HashSet<>();

    private boolean enabled;
    private String blockedCommandMessage;

    public KitItemGuard(DuckyPVP plugin, ArenaManager arena, KitManager kits) {
        this.plugin = plugin;
        this.arena = arena;
        this.kits = kits;
        reload();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("kit-protection.enabled", true);
        blockedCommandMessage = plugin.getConfig().getString(
                "kit-protection.messages.command-blocked",
                "&6&lDUCKY PVP &8» &7You cannot use &f/%command% &7while you have an arena kit."
        );

        blockedCommands.clear();
        List<String> configured = plugin.getConfig().getStringList("kit-protection.blocked-commands");
        for (String raw : configured.isEmpty() ? DEFAULT_BLOCKED_COMMANDS : configured) {
            String normalized = normalizeCommand(raw);
            if (!normalized.isBlank()) {
                blockedCommands.add(normalized);
            }
        }
    }

    private boolean hasKit(Player player) {
        return kits.hasBackup(player.getUniqueId()) || arena.isTrackedInside(player);
    }

    // --- Dropping and picking up -------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (enabled && kits.isKitItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        // Catch-all: a kit item should never exist as a loose item entity.
        if (enabled && kits.isKitItem(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!enabled) {
            return;
        }
        boolean kitItem = kits.isKitItem(event.getItem().getItemStack());
        Player player = event.getEntity() instanceof Player p ? p : null;

        if (kitItem) {
            if (player != null && hasKit(player) && arena.isInArena(player.getLocation())) {
                return;
            }
            event.setCancelled(true);
            event.getItem().remove();
            return;
        }

        // Kitted players can't collect outside items: they would be wiped when the
        // kit is removed, and containers like bundles could smuggle kit items out.
        if (player != null && hasKit(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArrowPickup(PlayerPickupArrowEvent event) {
        // Shot kit arrows can land outside the arena; arrow pickups don't fire EntityPickupItemEvent.
        if (!enabled || !(event.getArrow() instanceof AbstractArrow arrow) || !kits.isKitItem(arrow.getItemStack())) {
            return;
        }
        Player player = event.getPlayer();
        if (hasKit(player) && arena.isInArena(player.getLocation())) {
            return;
        }
        event.setCancelled(true);
        arrow.remove();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (enabled && kits.isKitItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (enabled && kits.isKitItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    // --- Containers, crafting and GUIs ------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled || !(event.getWhoClicked() instanceof Player player) || isCreative(player)) {
            return;
        }

        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }

        if (clicked.equals(top)) {
            if (kits.isKitItem(event.getCursor()) || kits.isKitItem(swapSourceItem(event, player))) {
                event.setCancelled(true);
            }
            return;
        }

        // Shift-click from the player's own inventory into a container. In the
        // plain inventory view (top = 2x2 crafting) shift-click only reorganizes.
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                && top.getType() != InventoryType.CRAFTING
                && kits.isKitItem(event.getCurrentItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!enabled || !(event.getWhoClicked() instanceof Player player) || isCreative(player)) {
            return;
        }
        if (!kits.isKitItem(event.getOldCursor())) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!enabled) {
            return;
        }
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (kits.isKitItem(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // --- Item frames, armor stands, allays and item-holding blocks ---------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!enabled) {
            return;
        }
        Entity target = event.getRightClicked();
        if (!(target instanceof ItemFrame) && !(target instanceof Allay)) {
            return;
        }
        ItemStack hand = event.getPlayer().getInventory().getItem(event.getHand());
        if (kits.isKitItem(hand)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (enabled && kits.isKitItem(event.getPlayerItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractBlock(PlayerInteractEvent event) {
        if (!enabled || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (kits.isKitItem(event.getItem()) && storesItems(event.getClickedBlock().getType())) {
            // Deny only the block interaction; using/placing the held item is unchanged.
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    private static boolean storesItems(Material type) {
        return switch (type) {
            case DECORATED_POT, CHISELED_BOOKSHELF, JUKEBOX, LECTERN, FLOWER_POT,
                 CAMPFIRE, SOUL_CAMPFIRE -> true;
            default -> type.name().endsWith("_SHELF");
        };
    }

    // --- Blocks broken or blown up inside the arena -----------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        // Placed kit blocks (obsidian, planks, cobble...) would otherwise drop as normal items,
        // including when broken from outside the arena. The arena is restored on reset anyway.
        if (enabled && arena.isInArena(event.getBlock().getLocation())) {
            event.getItems().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (enabled && arena.isInArena(event.getLocation())) {
            event.setYield(0.0f);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Block block = event.getBlock();
        if (enabled && arena.isInArena(block.getLocation())) {
            event.setYield(0.0f);
        }
    }

    // --- Commands that could turn kit items into money or store them -------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!enabled || !hasKit(event.getPlayer())) {
            return;
        }
        String label = commandLabel(event.getMessage());
        if (!blockedCommands.contains(label)) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&',
                blockedCommandMessage.replace("%command%", label)));
    }

    // --- Helpers -----------------------------------------------------------------

    private ItemStack swapSourceItem(InventoryClickEvent event, Player player) {
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return player.getInventory().getItemInOffHand();
        }
        int button = event.getHotbarButton();
        return button >= 0 ? player.getInventory().getItem(button) : null;
    }

    private static boolean isCreative(HumanEntity entity) {
        return entity.getGameMode() == GameMode.CREATIVE;
    }

    private static String commandLabel(String rawMessage) {
        String label = rawMessage == null ? "" : rawMessage.trim();
        if (label.startsWith("/")) {
            label = label.substring(1);
        }
        if (label.isBlank()) {
            return "";
        }
        return normalizeCommand(label.split("\\s+", 2)[0]);
    }

    private static String normalizeCommand(String input) {
        String result = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (result.startsWith("/")) {
            result = result.substring(1);
        }
        int colon = result.lastIndexOf(':');
        return colon >= 0 ? result.substring(colon + 1) : result;
    }
}
