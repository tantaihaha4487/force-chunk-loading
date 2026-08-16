package com.tantaihaha.forcechunkloading.force_chunk_loading;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configuration stored in the server's config directory. */
public final class ForceChunkConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = ForceChunkLoading.MOD_ID + ".json";
    private static final String DEFAULT_HEAD_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMTI4OWQ1YjE3ODYyNmVhMjNkMGIwYzNkMmRmNWMwODVlODM3NTA1NmJmNjg1YjVlZDViYjQ3N2ZlODQ3MmQ5NCJ9fX0=";

    public boolean allowPlacement = true;
    public int placementPermissionLevel = 0;
    public boolean allowPlayerRemoval = true;
    public boolean allowNonPlayerRemoval = false;
    public boolean showEnchantedParticles = true;
    public SoundConfig sounds = SoundConfig.defaults();
    public HeadConfig head = HeadConfig.defaults();
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
                JsonElement document = GSON.fromJson(reader, JsonElement.class);
                if (document == null || !document.isJsonObject()) {
                    throw new JsonParseException("configuration is empty");
                }
                boolean missingHeadConfig = !document.getAsJsonObject().has("head")
                        || document.getAsJsonObject().get("head").isJsonNull();
                boolean missingParticleConfig = !document.getAsJsonObject().has("showEnchantedParticles")
                        || document.getAsJsonObject().get("showEnchantedParticles").isJsonNull();
                boolean missingSoundConfig = !document.getAsJsonObject().has("sounds")
                        || document.getAsJsonObject().get("sounds").isJsonNull();
                ForceChunkConfig config = GSON.fromJson(document, ForceChunkConfig.class);
                config.normalize();
                if (missingHeadConfig || missingParticleConfig || missingSoundConfig) {
                    config.save(path, logger);
                }
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
        if (head == null) {
            head = HeadConfig.defaults();
        }
        head.normalize();
        if (sounds == null) {
            sounds = SoundConfig.defaults();
        }
        sounds.normalize();
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

    public static final class SoundConfig {
        private static final String DEFAULT_ACTIVATION = "minecraft:entity.player.levelup";
        private static final String DEFAULT_DEACTIVATION = "minecraft:entity.enderman.teleport";

        public boolean enabled = true;
        public String activation = DEFAULT_ACTIVATION;
        public String deactivation = DEFAULT_DEACTIVATION;

        public static SoundConfig defaults() {
            return new SoundConfig();
        }

        public void normalize() {
            if (activation == null || activation.isBlank() || Identifier.tryParse(activation) == null) {
                activation = DEFAULT_ACTIVATION;
            }
            if (deactivation == null || deactivation.isBlank() || Identifier.tryParse(deactivation) == null) {
                deactivation = DEFAULT_DEACTIVATION;
            }
        }
    }

    public static final class HeadConfig {
        public String texture = DEFAULT_HEAD_TEXTURE;

        public static HeadConfig defaults() {
            return new HeadConfig();
        }

        public void normalize() {
            if (texture == null || texture.isBlank() || !isBase64(texture)) {
                texture = DEFAULT_HEAD_TEXTURE;
            }
        }

        private static boolean isBase64(String value) {
            try {
                Base64.getDecoder().decode(value);
                return true;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }

    public static final class RecipeConfig {
        public boolean enabled = true;
        public List<String> pattern = List.of("DOD", "OEO", "DOD");
        public Map<String, String> ingredients = new LinkedHashMap<>();

        public static RecipeConfig defaults() {
            RecipeConfig config = new RecipeConfig();
            config.ingredients.put("O", "minecraft:obsidian");
            config.ingredients.put("E", "minecraft:ender_eye");
            config.ingredients.put("D", "minecraft:diamond_block");
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
