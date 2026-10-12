package dev.swap.session;

import dev.swap.Modifier;
import org.bukkit.Material;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies DEFLATION / INFLATION while a villager's trade window is open and puts the original offers
 * back (keeping the number of times each trade was used) when it closes.
 */
final class TradeAdjuster {
    private record Open(Merchant merchant, List<MerchantRecipe> originals) {
    }

    private final Map<UUID, Open> open = new HashMap<>();

    void onOpen(Player player, Merchant merchant, Modifier modifier) {
        if (!(merchant instanceof AbstractVillager)) {
            return;
        }
        restore(player);
        List<MerchantRecipe> originals = new ArrayList<>();
        List<MerchantRecipe> adjusted = new ArrayList<>();
        for (MerchantRecipe recipe : merchant.getRecipes()) {
            originals.add(copy(recipe));
            adjusted.add(adjust(recipe, modifier));
        }
        open.put(player.getUniqueId(), new Open(merchant, originals));
        merchant.setRecipes(adjusted);
    }

    void restore(Player player) {
        Open entry = open.remove(player.getUniqueId());
        if (entry != null) {
            put(entry);
        }
    }

    void restoreAll() {
        List<Open> entries = new ArrayList<>(open.values());
        open.clear();
        entries.forEach(this::put);
    }

    private void put(Open entry) {
        List<MerchantRecipe> current = entry.merchant().getRecipes();
        List<MerchantRecipe> restored = new ArrayList<>();
        for (int i = 0; i < entry.originals().size(); i++) {
            MerchantRecipe original = entry.originals().get(i);
            if (i < current.size()) {
                original.setUses(current.get(i).getUses());
            }
            restored.add(original);
        }
        entry.merchant().setRecipes(restored);
    }

    private static MerchantRecipe adjust(MerchantRecipe recipe, Modifier modifier) {
        MerchantRecipe adjusted = copy(recipe);
        List<ItemStack> ingredients = new ArrayList<>(recipe.getIngredients());
        if (modifier == Modifier.DEFLATION) {
            ingredients = new ArrayList<>(List.of(new ItemStack(Material.EMERALD, 1)));
        } else if (modifier == Modifier.INFLATION && !ingredients.isEmpty()) {
            ItemStack price = ingredients.get(0).clone();
            int current = currentCost(recipe, price);
            price.setAmount(Math.min(price.getMaxStackSize(), (int) Math.ceil(current * 1.1)));
            ingredients.set(0, price);
        }
        adjusted.setIngredients(ingredients);
        // The price is now baked into the ingredients, so drop vanilla demand / reputation adjustments.
        adjusted.setDemand(0);
        adjusted.setSpecialPrice(0);
        return adjusted;
    }

    /** The first ingredient's count as the villager would currently charge, including demand and discounts. */
    private static int currentCost(MerchantRecipe recipe, ItemStack price) {
        int base = price.getAmount();
        int demandBonus = Math.max(0, (int) Math.floor((float) (base * recipe.getDemand()) * recipe.getPriceMultiplier()));
        return Math.max(1, Math.min(price.getMaxStackSize(), base + demandBonus + recipe.getSpecialPrice()));
    }

    private static MerchantRecipe copy(MerchantRecipe recipe) {
        MerchantRecipe copy = new MerchantRecipe(recipe.getResult(), recipe.getUses(), recipe.getMaxUses(),
                recipe.hasExperienceReward(), recipe.getVillagerExperience(), recipe.getPriceMultiplier());
        copy.setIngredients(recipe.getIngredients());
        copy.setDemand(recipe.getDemand());
        copy.setSpecialPrice(recipe.getSpecialPrice());
        return copy;
    }
}
