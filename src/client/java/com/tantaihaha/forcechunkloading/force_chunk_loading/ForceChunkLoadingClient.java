package com.tantaihaha.forcechunkloading.force_chunk_loading;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.recipe.v1.sync.ClientRecipeSynchronizedEvent;

/** Optional client companion for dynamic recipe synchronization and recipe-book refreshes. */
public final class ForceChunkLoadingClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientRecipeSynchronizedEvent.EVENT.register((minecraft, recipes) -> {
            if (minecraft.player != null) {
                minecraft.player.getRecipeBook().rebuildCollections();
            }
        });
    }
}
