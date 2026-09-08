package com.tantaihaha.forcechunkloading.force_chunk_loading;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ForceChunkLoading implements ModInitializer {
    public static final String MOD_ID = "force_chunk_loading";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ForceChunkConfig config;

    @Override
    public void onInitialize() {
        config = ForceChunkConfig.load(LOGGER);
        ChunkLoadRecipe.initializeSynchronization();
        ChunkLoadCommand.initialize();
        ChunkLoadService.initialize();
    }

    public static ForceChunkConfig config() {
        if (config == null) {
            config = ForceChunkConfig.load(LOGGER);
        }
        return config;
    }

    /** Reloads configuration without replacing a valid active configuration on failure. */
    public static ForceChunkConfig.ReloadResult reloadConfig() {
        ForceChunkConfig.ReloadResult result = ForceChunkConfig.reload(LOGGER);
        if (result.success()) {
            config = result.config();
        }
        return result;
    }
}
