package com.tantaihaha.forcechunkloading.force_chunk_loading;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ForceChunkLoading implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("force_chunk_loading");

	@Override
	public void onInitialize() {
		LOGGER.info("Hello from Force Chunk Loading!");
	}
}
