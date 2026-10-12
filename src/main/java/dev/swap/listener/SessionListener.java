package dev.swap.listener;

import dev.swap.Settings;
import dev.swap.session.SessionManager;
import dev.swap.world.WorldService;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.MerchantInventory;

/** Join / quit / death / respawn handling, the superflat slime rule and villager price hooks. */
public final class SessionListener implements Listener {
    private final Settings settings;
    private final WorldService worlds;
    private final SessionManager manager;

    public SessionListener(Settings settings, WorldService worlds, SessionManager manager) {
        this.settings = settings;
        this.worlds = worlds;
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        manager.onDeath(event.getEntity(), event);
    }

    /** Everyone always respawns in the superflat world. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        event.setRespawnLocation(worlds.superflatSpawn());
        manager.afterRespawn(event.getPlayer());
    }

    /** The Dual Gemstone partner shares the chosen player's health, so natural regeneration is not doubled. */
    @EventHandler(ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player && manager.isLinkedPartner(player)) {
            EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
            if (reason == EntityRegainHealthEvent.RegainReason.SATIATED
                    || reason == EntityRegainHealthEvent.RegainReason.REGEN) {
                event.setCancelled(true);
            }
        }
    }

    /** Slimes are disabled in the superflat world. */
    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() == EntityType.SLIME
                && event.getLocation().getWorld().getName().equals(settings.superflatWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onTradeOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && event.getInventory() instanceof MerchantInventory trade) {
            manager.onMerchantOpen(player, trade.getMerchant());
        }
    }

    @EventHandler
    public void onTradeClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && event.getInventory() instanceof MerchantInventory) {
            manager.onMerchantClose(player);
        }
    }
}
