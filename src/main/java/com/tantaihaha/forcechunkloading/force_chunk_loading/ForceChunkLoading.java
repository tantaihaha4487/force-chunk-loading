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
        ChunkLoadService.initialize();
        LOGGER.info("Force Chunk Loading initialized (server-side vanilla player-head mode)");
    }

    public static ForceChunkConfig config() {
        if (config == null) {
            config = ForceChunkConfig.load(LOGGER);
        }
        return config;
    }
}
