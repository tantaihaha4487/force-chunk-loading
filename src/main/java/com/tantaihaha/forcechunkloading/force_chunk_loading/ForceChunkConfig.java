package com.tantaihaha.forcechunkloading.force_chunk_loading;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configuration stored in the server's config directory. */
public final class ForceChunkConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = ForceChunkLoading.MOD_ID + ".json";

    public boolean allowPlacement = true;
    public int placementPermissionLevel = 0;
    public boolean allowPlayerRemoval = true;
    public boolean allowNonPlayerRemoval = false;
    public RecipeConfig recipe = RecipeConfig.defaults();

    public static ForceChunkConfig load(Logger logger) {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                ForceChunkConfig config = new ForceChunkConfig();
                config.save(path, logger);
                return config;
            }

            try (Reader reader = Files.newBufferedReader(path)) {
                ForceChunkConfig config = GSON.fromJson(reader, ForceChunkConfig.class);
                if (config == null) {
                    throw new JsonParseException("configuration is empty");
                }
                config.normalize();
                return config;
            }
        } catch (IOException | RuntimeException exception) {
            logger.error("Could not read {}; using safe defaults", path, exception);
            ForceChunkConfig config = new ForceChunkConfig();
            config.normalize();
            return config;
        }
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public boolean canPlace(Player player) {
        if (!allowPlacement) {
            return false;
        }
        int requiredLevel = Math.max(0, Math.min(4, placementPermissionLevel));
        if (!(player.permissions() instanceof LevelBasedPermissionSet permissions)) {
            return requiredLevel == 0;
        }
        return permissions.level().isEqualOrHigherThan(PermissionLevel.byId(requiredLevel));
    }

    public void normalize() {
        placementPermissionLevel = Math.max(0, Math.min(4, placementPermissionLevel));
        if (recipe == null) {
            recipe = RecipeConfig.defaults();
        }
        recipe.normalize();
    }

    private void save(Path path, Logger logger) {
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(this, writer);
        } catch (IOException exception) {
            logger.warn("Could not create default configuration at {}", path, exception);
        }
    }

    public static final class RecipeConfig {
        public boolean enabled = true;
        public List<String> pattern = List.of("OOO", "OEO", "OOO");
        public Map<String, String> ingredients = new LinkedHashMap<>();

        public static RecipeConfig defaults() {
            RecipeConfig config = new RecipeConfig();
            config.ingredients.put("O", "minecraft:obsidian");
            config.ingredients.put("E", "minecraft:ender_eye");
            return config;
        }

        public void normalize() {
            if (pattern == null || pattern.isEmpty() || pattern.size() > 3) {
                pattern = defaults().pattern;
            }
            if (ingredients == null) {
                ingredients = defaults().ingredients;
            }
        }
    }
}
