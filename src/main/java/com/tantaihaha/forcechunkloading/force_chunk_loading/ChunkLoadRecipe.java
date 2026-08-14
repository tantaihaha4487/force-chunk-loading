package com.tantaihaha.forcechunkloading.force_chunk_loading;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/** Creates a vanilla shaped recipe from the server config. */
public final class ChunkLoadRecipe {
    public static final ResourceKey<Recipe<?>> ID = ResourceKey.create(
            Registries.RECIPE,
            Identifier.fromNamespaceAndPath(ForceChunkLoading.MOD_ID, "chunk_load")
    );

    private ChunkLoadRecipe() {
    }

    public static RecipeHolder<ShapedRecipe> createHolder(Logger logger) {
        ShapedRecipe recipe = create(logger);
        return recipe == null ? null : new RecipeHolder<>(ID, recipe);
    }

    private static ShapedRecipe create(Logger logger) {
        ForceChunkConfig.RecipeConfig config = ForceChunkLoading.config().recipe;
        if (!config.enabled) {
            return null;
        }
        if (config.pattern == null || config.pattern.isEmpty() || config.pattern.size() > 3) {
            logger.error("Invalid chunk-load recipe pattern; recipe is disabled");
            return null;
        }

        Map<Character, Ingredient> key = new LinkedHashMap<>();
        try {
            for (String row : config.pattern) {
                if (row == null || row.length() > 3) {
                    logger.error("Invalid chunk-load recipe row {}; recipe is disabled", row);
                    return null;
                }
                for (int index = 0; index < row.length(); index++) {
                    char symbol = row.charAt(index);
                    if (symbol == ' ' || key.containsKey(symbol)) {
                        continue;
                    }
                    String itemId = config.ingredients.get(String.valueOf(symbol));
                    if (itemId == null || itemId.isBlank()) {
                        logger.error("No item configured for chunk-load recipe symbol '{}'; recipe is disabled", symbol);
                        return null;
                    }
                    Identifier identifier = Identifier.tryParse(itemId);
                    if (identifier == null) {
                        logger.error("Invalid item id '{}' in chunk-load recipe; recipe is disabled", itemId);
                        return null;
                    }
                    var item = BuiltInRegistries.ITEM.getValue(identifier);
                    if (item == null || item == Items.AIR) {
                        logger.error("Unknown item '{}' in chunk-load recipe; recipe is disabled", itemId);
                        return null;
                    }
                    key.put(symbol, Ingredient.of(item));
                }
            }

            ShapedRecipePattern pattern = ShapedRecipePattern.of(key, config.pattern);
            ItemStackTemplate result = ChunkLoadService.createMarkerTemplate();
            return new ShapedRecipe(
                    new Recipe.CommonInfo(true),
                    new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ForceChunkLoading.MOD_ID),
                    pattern,
                    result
            );
        } catch (RuntimeException exception) {
            logger.error("Could not create configured chunk-load recipe; recipe is disabled", exception);
            return null;
        }
    }
}
