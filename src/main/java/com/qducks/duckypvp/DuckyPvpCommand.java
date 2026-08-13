package com.qducks.duckypvp;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DuckyPvpCommand implements CommandExecutor, TabCompleter {
    private final DuckyPVP plugin;
    private final ArenaManager arena;
    private final KitManager kits;

    public DuckyPvpCommand(DuckyPVP plugin, ArenaManager arena, KitManager kits) {
        this.plugin = plugin;
        this.arena = arena;
        this.kits = kits;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("duckypvp.admin")) {
            sender.sendMessage(color("&cYou do not have permission."));
            return true;
        }

        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                sender.sendMessage(color("&6&lDuckyPVP &8» &7Region: &f" + arena.getWorldName() + ":" + arena.getRegionName()));
                sender.sendMessage(color("&6&lDuckyPVP &8» &7Active kit: &f" + kits.getActiveDisplayName()));
                sender.sendMessage(color("&6&lDuckyPVP &8» &7Changed blocks tracked: &f" + arena.getChangedBlockCount()));
                sender.sendMessage(color("&6&lDuckyPVP &8» &7Next reset: &f" + arena.getSecondsUntilReset() + "s"));
            }
            case "reset" -> {
                arena.resetArena(true);
                sender.sendMessage(color("&aArena reset and a new kit was selected."));
            }
            case "reroll" -> {
                arena.rerollKit();
                sender.sendMessage(color("&aNew active kit: &f" + kits.getActiveDisplayName()));
            }
            case "reload" -> {
                plugin.reloadDuckyPvp();
                sender.sendMessage(color("&aDuckyPVP configuration reloaded."));
            }
            default -> sender.sendMessage(color("&e/duckypvp <status|reset|reroll|reload>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("duckypvp.admin")) {
            return List.of();
        }

        String input = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : List.of("status", "reset", "reroll", "reload")) {
            if (option.startsWith(input)) {
                matches.add(option);
            }
        }
        return matches;
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
