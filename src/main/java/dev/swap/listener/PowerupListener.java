package dev.swap.listener;

import dev.swap.item.Items;
import dev.swap.session.SessionManager;
import dev.swap.util.Chat;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Right-click handling for powerups, and guards that keep temporary items from leaving the player. */
public final class PowerupListener implements Listener {
    private final Items items;
    private final SessionManager manager;

    public PowerupListener(Items items, SessionManager manager) {
        this.items = items;
        this.manager = manager;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();

        if (items.isTimeExtender(held)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            String error = manager.validateExtend(player);
            if (error != null) {
                player.sendMessage(Chat.msg("<red><error>", Chat.text("error", error)));
                return;
            }
            consumeHeld(player, held);
            manager.applyExtend(player);
        } else if (items.isDualGemstone(held)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            player.sendMessage(Chat.msg("Use <yellow>/swap bring \\<player></yellow> during your session to take someone with you."));
        }
    }

    private void consumeHeld(Player player, ItemStack held) {
        if (held.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            ItemStack reduced = held.clone();
            reduced.setAmount(held.getAmount() - 1);
            player.getInventory().setItemInMainHand(reduced);
        }
    }

    // ---- temporary items stay with their owner ----

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (items.isTemporary(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (isPlayerOnly(top)) {
            return;
        }
        boolean topClicked = event.getClickedInventory() == top;
        boolean blocked = false;
        if (!topClicked && event.isShiftClick() && items.isTemporary(event.getCurrentItem())) {
            blocked = true; // shift-click into a chest
        } else if (topClicked && items.isTemporary(event.getCursor())) {
            blocked = true; // placing from the cursor
        } else if (topClicked && event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0
                && items.isTemporary(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()))) {
            blocked = true;
        } else if (topClicked && event.getClick() == ClickType.SWAP_OFFHAND
                && items.isTemporary(event.getWhoClicked().getInventory().getItemInOffHand())) {
            blocked = true;
        }
        if (blocked) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (isPlayerOnly(top) || !items.isTemporary(event.getOldCursor())) {
            return;
        }
        int topSize = top.getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    private static boolean isPlayerOnly(Inventory top) {
        InventoryType type = top.getType();
        return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE || type == InventoryType.PLAYER;
    }
}
