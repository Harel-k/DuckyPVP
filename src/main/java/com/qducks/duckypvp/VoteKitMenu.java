package com.qducks.duckypvp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public final class VoteKitMenu implements Listener, CommandExecutor {
    private final DuckyPVP plugin;
    private final KitManager kits;
    private final VoteManager votes;
    private final NamespacedKey kitKey;

    public VoteKitMenu(DuckyPVP plugin, KitManager kits, VoteManager votes) {
        this.plugin = plugin;
        this.kits = kits;
        this.votes = votes;
        this.kitKey = new NamespacedKey(plugin, "vote-kit-id");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /votekit.");
            return true;
        }
        open(player);
        return true;
    }

    public void open(Player player) {
        List<String> ids = kits.getKitIds();
        int size = Math.max(9, Math.min(54, ((ids.size() + 8) / 9) * 9));
        String title = color(plugin.getConfig().getString("ui.vote-menu.title", "&8Vote for the Next Kit"));
        Inventory inventory = Bukkit.createInventory(null, size, title);

        String ownVote = votes.getVote(player.getUniqueId());
        String queuedKit = votes.getQueuedKit();

        for (int i = 0; i < ids.size() && i < size; i++) {
            String kitId = ids.get(i);
            Material icon = kits.getIconMaterial(kitId);
            ItemStack item = new ItemStack(icon);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }

            meta.setDisplayName(kits.getDisplayName(kitId));
            List<String> lore = new ArrayList<>();
            for (String line : kits.getDescription(kitId)) {
                lore.add(color("&7" + line));
            }
            if (!lore.isEmpty()) {
                lore.add("");
            }
            lore.add(color("&eVotes: &f" + votes.getVoteCount(kitId)));
            if (kitId.equalsIgnoreCase(ownVote)) {
                lore.add(color("&a✔ Your vote"));
            } else {
                lore.add(color("&bClick to vote"));
            }
            if (kitId.equalsIgnoreCase(queuedKit)) {
                lore.add(color("&6⚡ Admin queued for next reset"));
            }
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(kitKey, PersistentDataType.STRING, kitId);
            item.setItemMeta(meta);
            inventory.setItem(i, item);
        }

        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = color(plugin.getConfig().getString("ui.vote-menu.title", "&8Vote for the Next Kit"));
        if (!event.getView().getTitle().equals(title)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) {
            return;
        }

        String kitId = clicked.getItemMeta().getPersistentDataContainer().get(kitKey, PersistentDataType.STRING);
        if (kitId == null || !votes.castVote(player.getUniqueId(), kitId)) {
            return;
        }

        player.sendMessage(color("&6&lDUCKY PVP &8» &aYou voted for " + kits.getDisplayName(kitId) + "&a."));
        open(player);
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }
}
