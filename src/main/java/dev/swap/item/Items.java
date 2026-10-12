package dev.swap.item;

import dev.swap.util.Chat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.function.Predicate;

/** Powerup and temporary items, plus their crafting recipes. */
public final class Items {
    private static final String DUAL_ID = "dual_gemstone";
    private static final String EXTENDER_ID = "time_extender";

    private final NamespacedKey itemKey;
    private final NamespacedKey tempKey;
    private final NamespacedKey dualRecipeKey;
    private final NamespacedKey extenderRecipeKey;

    public Items(JavaPlugin plugin) {
        this.itemKey = new NamespacedKey(plugin, "item");
        this.tempKey = new NamespacedKey(plugin, "temporary");
        this.dualRecipeKey = new NamespacedKey(plugin, DUAL_ID);
        this.extenderRecipeKey = new NamespacedKey(plugin, EXTENDER_ID);
    }

    // ---- powerups ----

    public ItemStack dualGemstone() {
        return powerup(Material.AMETHYST_SHARD, DUAL_ID,
                "<gradient:#f472b6:#8b5cf6>Dual Gemstone",
                List.of("<gray>Use <yellow>/swap bring \\<player></yellow> during your",
                        "<gray>session to take one other player with you.",
                        "<gray>You share your inventory and health."));
    }

    public ItemStack timeExtender() {
        return powerup(Material.CLOCK, EXTENDER_ID,
                "<gradient:#38bdf8:#34d399>Time Extender",
                List.of("<gray>Right-click during your session to extend it",
                        "<gray>to <white>20 minutes</white> (cooldown becomes <white>10 minutes</white>)."));
    }

    private ItemStack powerup(Material material, String id, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Chat.mm(name).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(l -> Chat.mm(l).decoration(TextDecoration.ITALIC, false)).toList());
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isDualGemstone(ItemStack stack) {
        return hasId(stack, DUAL_ID);
    }

    public boolean isTimeExtender(ItemStack stack) {
        return hasId(stack, EXTENDER_ID);
    }

    private boolean hasId(ItemStack stack, String id) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        String value = stack.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return id.equals(value);
    }

    // ---- temporary modifier items ----

    public ItemStack fortunePickaxe() {
        return temporary(Material.DIAMOND_PICKAXE, Enchantment.FORTUNE, "Fortune V Pickaxe");
    }

    public ItemStack lootingSword() {
        return temporary(Material.DIAMOND_SWORD, Enchantment.LOOTING, "Looting V Sword");
    }

    private ItemStack temporary(Material material, Enchantment enchantment, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Chat.mm("<gradient:#1d4ed8:#38bdf8>" + name).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Chat.mm("<gray>Temporary: removed when your session ends.")
                .decoration(TextDecoration.ITALIC, false)));
        meta.addEnchant(enchantment, 5, true);
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(tempKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isTemporary(ItemStack stack) {
        return stack != null
                && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(tempKey, PersistentDataType.BYTE);
    }

    /**
     * Gives a temporary item. If the inventory is full the item replaces the last hotbar slot and the
     * displaced item is dropped at the player's feet, so the temporary item can never be lost to a full bag.
     */
    public void giveTemporary(Player player, ItemStack temp) {
        PlayerInventory inventory = player.getInventory();
        if (inventory.firstEmpty() >= 0) {
            inventory.addItem(temp);
            return;
        }
        ItemStack displaced = inventory.getItem(8);
        inventory.setItem(8, temp);
        if (displaced != null) {
            player.getWorld().dropItem(player.getLocation(), displaced);
        }
    }

    /** Removes every temporary item the player carries. Callers should close open inventories first. */
    public void removeTemporary(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            if (isTemporary(contents[i])) {
                contents[i] = null;
                changed = true;
            }
        }
        if (changed) {
            inventory.setContents(contents);
        }
        if (isTemporary(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    /** Consumes one matching item from anywhere in the player's inventory. */
    public boolean consumeOne(Player player, Predicate<ItemStack> matcher) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack != null && matcher.test(stack)) {
                if (stack.getAmount() <= 1) {
                    inventory.setItem(i, null);
                } else {
                    ItemStack reduced = stack.clone();
                    reduced.setAmount(stack.getAmount() - 1);
                    inventory.setItem(i, reduced);
                }
                return true;
            }
        }
        return false;
    }

    // ---- recipes ----

    public void registerRecipes() {
        ShapedRecipe dual = new ShapedRecipe(dualRecipeKey, dualGemstone());
        dual.shape("DDD", "GRG", "DDD");
        dual.setIngredient('D', Material.DIAMOND);
        dual.setIngredient('G', Material.GOLD_INGOT);
        dual.setIngredient('R', Material.REDSTONE_BLOCK);
        Bukkit.addRecipe(dual);

        ShapedRecipe extender = new ShapedRecipe(extenderRecipeKey, timeExtender());
        extender.shape("GBG", "GSG", "GEG");
        extender.setIngredient('G', Material.GLASS);
        extender.setIngredient('B', Material.DIAMOND_BLOCK);
        extender.setIngredient('S', Material.SAND);
        extender.setIngredient('E', Material.EMERALD_BLOCK);
        Bukkit.addRecipe(extender);
    }

    public void unregisterRecipes() {
        Bukkit.removeRecipe(dualRecipeKey);
        Bukkit.removeRecipe(extenderRecipeKey);
    }

    public void discoverRecipes(Player player) {
        player.discoverRecipes(List.of(dualRecipeKey, extenderRecipeKey));
    }
}
