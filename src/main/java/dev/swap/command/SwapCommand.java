package dev.swap.command;

import dev.swap.Modifier;
import dev.swap.Settings;
import dev.swap.SwapPlugin;
import dev.swap.item.Items;
import dev.swap.session.SessionManager;
import dev.swap.util.Chat;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class SwapCommand implements CommandExecutor, TabCompleter {
    private static final List<String> PLAYER_SUBS = List.of("status", "bring", "extend");
    private static final List<String> ADMIN_SUBS = List.of("start", "end", "give", "reload");

    private final SwapPlugin plugin;
    private final Settings settings;
    private final SessionManager manager;
    private final Items items;

    public SwapCommand(SwapPlugin plugin, Settings settings, SessionManager manager, Items items) {
        this.plugin = plugin;
        this.settings = settings;
        this.manager = manager;
        this.items = items;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "bring" -> bring(sender, args);
            case "extend" -> extend(sender);
            case "start", "end", "give", "reload" -> {
                if (!sender.hasPermission("swap.admin")) {
                    sender.sendMessage(Chat.msg("<red>You don't have permission to do that."));
                    return true;
                }
                switch (sub) {
                    case "start" -> start(sender, args);
                    case "end" -> end(sender);
                    case "give" -> give(sender, args);
                    default -> reload(sender);
                }
            }
            default -> sender.sendMessage(Chat.msg("<red>Unknown sub-command. Try <white>/swap status</white>."));
        }
        return true;
    }

    private void status(CommandSender sender) {
        for (String line : manager.statusLines()) {
            sender.sendMessage(Chat.msg(line));
        }
    }

    private void bring(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("swap.play")) {
            sender.sendMessage(Chat.msg("<red>Only players can use the Dual Gemstone."));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Chat.msg("<red>Usage: /swap bring \\<player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        String error = manager.validateBring(player, target);
        if (error != null) {
            player.sendMessage(Chat.msg("<red><error>", Chat.text("error", error)));
            return;
        }
        if (!items.consumeOne(player, items::isDualGemstone)) {
            player.sendMessage(Chat.msg("<red>You need a Dual Gemstone in your inventory."));
            return;
        }
        manager.applyBring(player, target);
    }

    private void extend(CommandSender sender) {
        if (!(sender instanceof Player player) || !player.hasPermission("swap.play")) {
            sender.sendMessage(Chat.msg("<red>Only players can use the Time Extender."));
            return;
        }
        String error = manager.validateExtend(player);
        if (error != null) {
            player.sendMessage(Chat.msg("<red><error>", Chat.text("error", error)));
            return;
        }
        if (!items.consumeOne(player, items::isTimeExtender)) {
            player.sendMessage(Chat.msg("<red>You need a Time Extender in your inventory."));
            return;
        }
        manager.applyExtend(player);
    }

    private void start(CommandSender sender, String[] args) {
        Player chosen = null;
        if (args.length >= 2) {
            chosen = Bukkit.getPlayerExact(args[1]);
        } else if (sender instanceof Player self) {
            chosen = self;
        }
        if (chosen == null) {
            sender.sendMessage(Chat.msg("<red>Usage: /swap start \\<player> [modifier|none]"));
            return;
        }
        Modifier modifier = null;
        boolean specified = false;
        if (args.length >= 3) {
            specified = true;
            if (!args[2].equalsIgnoreCase("none")) {
                modifier = Modifier.parse(args[2]);
                if (modifier == null) {
                    sender.sendMessage(Chat.msg("<red>Unknown modifier. Use one of: <white><list>",
                            Chat.text("list", String.join(", ", Arrays.stream(Modifier.values()).map(m -> m.name().toLowerCase(Locale.ROOT)).toList()))));
                    return;
                }
            }
        }
        String error = manager.forceStart(chosen, modifier, specified);
        if (error != null) {
            sender.sendMessage(Chat.msg("<red><error>", Chat.text("error", error)));
        }
    }

    private void end(CommandSender sender) {
        manager.forceEnd();
        sender.sendMessage(Chat.msg("Session ended."));
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Chat.msg("<red>Usage: /swap give \\<player> \\<dual|extender> [amount]"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Chat.msg("<red>That player is not online."));
            return;
        }
        ItemStack stack;
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "dual", "dual_gemstone" -> stack = items.dualGemstone();
            case "extender", "time_extender" -> stack = items.timeExtender();
            default -> {
                sender.sendMessage(Chat.msg("<red>Item must be <white>dual</white> or <white>extender</white>."));
                return;
            }
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
            } catch (NumberFormatException e) {
                sender.sendMessage(Chat.msg("<red>Amount must be a number."));
                return;
            }
        }
        stack.setAmount(amount);
        target.getInventory().addItem(stack).values()
                .forEach(left -> target.getWorld().dropItem(target.getLocation(), left));
        sender.sendMessage(Chat.msg("Gave <yellow><amount>x</yellow> to <yellow><player></yellow>.",
                Chat.text("amount", String.valueOf(amount)), Chat.text("player", target.getName())));
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        settings.load(plugin.getConfig());
        sender.sendMessage(Chat.msg("Configuration reloaded. <gray>(World names need a restart.)"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(PLAYER_SUBS);
            if (sender.hasPermission("swap.admin")) {
                out.addAll(ADMIN_SUBS);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("bring")
                || (sender.hasPermission("swap.admin")
                && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("give"))))) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 3 && sender.hasPermission("swap.admin")) {
            if (args[0].equalsIgnoreCase("give")) {
                out.addAll(List.of("dual", "extender"));
            } else if (args[0].equalsIgnoreCase("start")) {
                out.add("none");
                for (Modifier m : Modifier.values()) {
                    out.add(m.name().toLowerCase(Locale.ROOT));
                }
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
