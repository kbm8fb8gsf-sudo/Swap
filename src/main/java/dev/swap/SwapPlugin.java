package dev.swap;

import dev.swap.command.SwapCommand;
import dev.swap.item.Items;
import dev.swap.listener.PowerupListener;
import dev.swap.listener.SessionListener;
import dev.swap.session.SessionManager;
import dev.swap.world.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Swap: everyone lives in a superflat world, and every cycle one random player is sent to the main
 * survival world for a timed session, optionally with powerups and a random modifier.
 */
public final class SwapPlugin extends JavaPlugin {
    private Items items;
    private SessionManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Settings settings = new Settings();
        settings.load(getConfig());

        WorldService worlds = new WorldService(this, settings);
        worlds.ensureWorlds();

        items = new Items(this);
        items.registerRecipes();

        manager = new SessionManager(this, settings, worlds, items);

        Bukkit.getPluginManager().registerEvents(new SessionListener(settings, worlds, manager), this);
        Bukkit.getPluginManager().registerEvents(new PowerupListener(items, manager), this);

        SwapCommand command = new SwapCommand(this, settings, manager, items);
        getCommand("swap").setExecutor(command);
        getCommand("swap").setTabCompleter(command);

        manager.start();
        for (Player player : Bukkit.getOnlinePlayers()) {
            manager.onJoin(player); // covers /reload: everyone goes to superflat
        }
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
        if (items != null) {
            items.unregisterRecipes();
        }
    }
}
