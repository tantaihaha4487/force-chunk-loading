package com.tantaihaha.forcechunkloading.force_chunk_loading;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.ChunkPos;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server-side lifecycle and force-loading logic. */
public final class ChunkLoadService {
    private static final String MARKER_KEY = "force_chunk_loading";
    private static final String MARKER_VALUE = "chunk_load";
    private static final int PARTICLE_INTERVAL_TICKS = 5;
    private static final int PARTICLE_COUNT = 2;

    private static final Map<ServerLevel, ChunkLoadData> DATA = new WeakHashMap<>();

    private ChunkLoadService() {
    }

    public static void initialize() {
        ServerLevelEvents.LOAD.register((server, level) -> {
            ChunkLoadData data = data(level);
            for (BlockPos position : data.positions()) {
                level.setChunkForced(position.getX() >> 4, position.getZ() >> 4, true);
            }
        });
        ServerLevelEvents.UNLOAD.register((server, level) -> DATA.remove(level));

        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
            if (isMarkerBlock(level, blockEntity.getBlockPos(), blockEntity.getBlockState(), blockEntity)
                    && shouldDiscoverUntrackedMarkers(level, blockEntity.getBlockPos())) {
                track(level, blockEntity.getBlockPos());
            }
        });

        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> scanChunk(level, chunk));

        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (!(level instanceof ServerLevel serverLevel) || !isMarkerBlock(level, pos, state, blockEntity)) {
                return true;
            }
            return ForceChunkLoading.config().allowPlayerRemoval;
        });

        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level instanceof ServerLevel serverLevel && isMarkerState(state)) {
                boolean tracked = data(serverLevel).positions().contains(pos);
                untrack(serverLevel, pos);
                if (tracked && player instanceof ServerPlayer serverPlayer) {
                    sendFeedback(serverPlayer, false);
                }
            }
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(level instanceof ServerLevel) || !isMarkerItem(player.getItemInHand(hand))) {
                return InteractionResult.PASS;
            }
            return ForceChunkLoading.config().canPlace(player) ? InteractionResult.PASS : InteractionResult.FAIL;
        });

        ServerTickEvents.END_LEVEL_TICK.register(level -> {
            validateTrackedBlocks(level);
            spawnMarkerParticles(level);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) {
                return;
            }

            RecipeHolder<ShapedRecipe> recipe = ChunkLoadRecipe.createHolder(ForceChunkLoading.LOGGER);
            if (recipe == null) {
                return;
            }

            for (var player : server.getPlayerList().getPlayers()) {
                if (!player.getRecipeBook().contains(ChunkLoadRecipe.ID)
                        && ChunkLoadRecipe.hasConfiguredIngredient(recipe, player)) {
                    player.getRecipeBook().addRecipes(List.of(recipe), player);
                }
            }
        });
    }

    /** Called by the server-authoritative BlockItem mixin after vanilla placement succeeds. */
    public static void onPlaced(ServerLevel level, BlockPos position, ItemStack stack, ServerPlayer player) {
        if (isMarkerItem(stack) && isMarkerBlock(level, position)) {
            track(level, position);
            if (player != null) {
                sendFeedback(player, true);
            }
        }
    }

    public static ItemStack createMarkerStack() {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.applyComponents(markerComponents());
        return stack;
    }

    public static void dropMarkerItem(ServerLevel level, BlockPos position, Entity breaker) {
        if (breaker instanceof ServerPlayer || ForceChunkLoading.config().allowNonPlayerRemoval) {
            Block.popResource(level, position, createMarkerStack());
        }
    }

    static ItemStackTemplate createMarkerTemplate() {
        return new ItemStackTemplate(Items.PLAYER_HEAD, markerComponents());
    }

    private static DataComponentPatch markerComponents() {
        return DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("Chunk load"))
                .set(DataComponents.PROFILE, configuredProfile())
                .set(DataComponents.CUSTOM_DATA, CustomData.of(markerTag()))
                .build();
    }

    private static ResolvableProfile configuredProfile() {
        String texture = ForceChunkLoading.config().head.texture;
        Multimap<String, Property> properties = ArrayListMultimap.create();
        properties.put("textures", new Property("textures", texture));
        UUID profileId = UUID.nameUUIDFromBytes(
                (ForceChunkLoading.MOD_ID + ":" + texture).getBytes(StandardCharsets.UTF_8)
        );
        // The profile name is internal and must satisfy Minecraft's username rules;
        // the visible item name remains the separate "Chunk load" custom name.
        GameProfile profile = new GameProfile(profileId, "ChunkLoad", new PropertyMap(properties));
        return ResolvableProfile.createResolved(profile);
    }

    public static boolean isMarkerItem(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.PLAYER_HEAD)) {
            return false;
        }
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData != null && MARKER_VALUE.equals(customData.copyTag().getStringOr(MARKER_KEY, ""));
    }

    public static boolean isMarkerBlock(ServerLevel level, BlockPos position) {
        return isMarkerBlock(level, position, level.getBlockState(position), level.getBlockEntity(position));
    }

    public static boolean isMarkerBlock(Level level, BlockPos position, BlockState state, BlockEntity blockEntity) {
        if (!isMarkerState(state) || !(blockEntity instanceof SkullBlockEntity skull)) {
            return false;
        }
        CustomData customData = skull.components().get(DataComponents.CUSTOM_DATA);
        return customData != null && MARKER_VALUE.equals(customData.copyTag().getStringOr(MARKER_KEY, ""));
    }

    private static boolean isMarkerState(BlockState state) {
        return state.is(Blocks.PLAYER_HEAD) || state.is(Blocks.PLAYER_WALL_HEAD);
    }

    private static void scanChunk(ServerLevel level, LevelChunk chunk) {
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (isMarkerBlock(level, blockEntity.getBlockPos(), blockEntity.getBlockState(), blockEntity)
                    && shouldDiscoverUntrackedMarkers(level, blockEntity.getBlockPos())) {
                track(level, blockEntity.getBlockPos());
            }
        }
    }

    private static ChunkLoadData data(ServerLevel level) {
        return DATA.computeIfAbsent(level, ignored -> level.getDataStorage().computeIfAbsent(ChunkLoadData.TYPE));
    }

    private static boolean shouldDiscoverUntrackedMarkers(ServerLevel level, BlockPos position) {
        return ForceChunkLoading.config().allowNonPlayerRemoval || data(level).positions().contains(position);
    }

    private static void track(ServerLevel level, BlockPos position) {
        ChunkLoadData data = data(level);
        data.add(position);
        ChunkPos chunk = ChunkPos.containing(position);
        level.setChunkForced(chunk.x(), chunk.z(), true);
    }

    private static void untrack(ServerLevel level, BlockPos position) {
        ChunkLoadData data = data(level);
        if (!data.remove(position)) {
            return;
        }
        ChunkPos chunk = ChunkPos.containing(position);
        if (data.positions().stream().noneMatch(other -> ChunkPos.containing(other).equals(chunk))) {
            level.setChunkForced(chunk.x(), chunk.z(), false);
        }
    }

    private static void validateTrackedBlocks(ServerLevel level) {
        ChunkLoadData data = data(level);
        for (BlockPos position : data.positions()) {
            if (isMarkerBlock(level, position)) {
                continue;
            }

            if (ForceChunkLoading.config().allowNonPlayerRemoval) {
                untrack(level, position);
            } else {
                restoreMarker(level, position);
            }
        }
    }

    private static void spawnMarkerParticles(ServerLevel level) {
        if (!ForceChunkLoading.config().showEnchantedParticles
                || level.getGameTime() % PARTICLE_INTERVAL_TICKS != 0) {
            return;
        }

        for (BlockPos position : data(level).positions()) {
            if (!isMarkerBlock(level, position)) {
                continue;
            }
            level.sendParticles(
                    ParticleTypes.ENCHANT,
                    position.getX() + 0.5,
                    position.getY() + 0.9,
                    position.getZ() + 0.5,
                    PARTICLE_COUNT,
                    0.35,
                    0.35,
                    0.35,
                    0.05
            );
        }
    }

    private static void sendFeedback(ServerPlayer player, boolean activated) {
        sendActionbar(player, activated);

        ForceChunkConfig.SoundConfig config = ForceChunkLoading.config().sounds;
        if (!config.enabled) {
            return;
        }

        String configuredId = activated ? config.activation : config.deactivation;
        Identifier identifier = Identifier.tryParse(configuredId);
        if (identifier == null) {
            return;
        }
        SoundEvent soundEvent = BuiltInRegistries.SOUND_EVENT.getValue(identifier);
        if (soundEvent == null) {
            return;
        }

        player.connection.send(new ClientboundSoundPacket(
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(soundEvent),
                SoundSource.MASTER,
                player.getX(),
                player.getY(),
                player.getZ(),
                activated ? 0.6F : 1.0F,
                activated ? 0.7F : 0.5F,
                player.getRandom().nextLong()
        ));
    }

    private static void sendActionbar(ServerPlayer player, boolean activated) {
        int color = activated ? 0x55EA80 : 0xFF5555;
        MutableComponent prefix = Component.literal("(i) ")
                .withStyle(Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(0xFFD700)));
        MutableComponent name = Component.literal("Force Chunk Loading "
                        + (activated ? "Activated" : "Deactivated"))
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
        player.sendOverlayMessage(prefix.append(name));
    }

    private static void restoreMarker(ServerLevel level, BlockPos position) {
        BlockState state = Blocks.PLAYER_HEAD.defaultBlockState();
        level.setBlock(position, state, 3);
        BlockEntity blockEntity = level.getBlockEntity(position);
        if (blockEntity != null) {
            blockEntity.applyComponentsFromItemStack(createMarkerStack());
            blockEntity.setChanged();
        }
        ChunkPos chunk = ChunkPos.containing(position);
        level.setChunkForced(chunk.x(), chunk.z(), true);
    }

    private static CompoundTag markerTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString(MARKER_KEY, MARKER_VALUE);
        return tag;
    }

}
