package com.tantaihaha.forcechunkloading.force_chunk_loading.mixin;

import com.tantaihaha.forcechunkloading.force_chunk_loading.ChunkLoadRecipe;
import com.tantaihaha.forcechunkloading.force_chunk_loading.ForceChunkLoading;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @Shadow
    private RecipeMap recipes;

    @Inject(
            method = "apply(Lnet/minecraft/world/item/crafting/RecipeMap;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("TAIL")
    )
    private void forceChunkLoading$injectConfiguredRecipe(
            RecipeMap prepared,
            ResourceManager resourceManager,
            ProfilerFiller profiler,
            CallbackInfo callbackInfo
    ) {
        List<RecipeHolder<?>> allRecipes = new ArrayList<>(this.recipes.values());
        allRecipes.removeIf(holder -> holder.id().equals(ChunkLoadRecipe.ID));
        RecipeHolder<?> configured = ChunkLoadRecipe.createHolder(ForceChunkLoading.LOGGER);
        if (configured != null) {
            allRecipes.add(configured);
        }
        // RecipeMap.create retains Fabric's synchronized-recipe metadata, allowing the
        // configured vanilla-shaped recipe to reach clients that support recipe sync.
        this.recipes = RecipeMap.create(allRecipes);
    }
}
