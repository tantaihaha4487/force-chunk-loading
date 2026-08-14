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
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server-side lifecycle and force-loading logic. */
public final class ChunkLoadService {
    private static final String MARKER_KEY = "force_chunk_loading";
    private static final String MARKER_VALUE = "chunk_load";
    private static final String EARTH_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmRkZTU5NGRlYWQ4OGIzNWJjMjFhZDFhYjIzOGRjYWU0MTEyNTNlMzRhNTg1ZDkyNTI1OGNlNjc0YzY0MjYxNyJ9fX0=";
    private static final UUID EARTH_PROFILE_ID = UUID.fromString("32715d6a-2c4d-4e2e-a3b5-64ca4f8a7f31");

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
                untrack(serverLevel, pos);
            }
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(level instanceof ServerLevel) || !isMarkerItem(player.getItemInHand(hand))) {
                return InteractionResult.PASS;
            }
            return ForceChunkLoading.config().canPlace(player) ? InteractionResult.PASS : InteractionResult.FAIL;
        });

        ServerTickEvents.END_LEVEL_TICK.register(ChunkLoadService::validateTrackedBlocks);
    }

    /** Called by the server-only BlockItem mixin after vanilla placement succeeds. */
    public static void onPlaced(ServerLevel level, BlockPos position, ItemStack stack) {
        if (isMarkerItem(stack) && isMarkerBlock(level, position)) {
            track(level, position);
        }
    }

    public static ItemStack createMarkerStack() {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Chunk load"));
        stack.set(DataComponents.PROFILE, earthProfile());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(markerTag()));
        return stack;
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

    private static boolean isMarkerBlock(Level level, BlockPos position, BlockState state, BlockEntity blockEntity) {
        if (!isMarkerState(state) || !(blockEntity instanceof SkullBlockEntity skull)) {
            return false;
        }
        return hasEarthTexture(skull.getOwnerProfile());
    }

    private static boolean isMarkerState(BlockState state) {
        return state.is(Blocks.PLAYER_HEAD) || state.is(Blocks.PLAYER_WALL_HEAD);
    }

    private static boolean hasEarthTexture(ResolvableProfile profile) {
        if (profile == null || profile.partialProfile() == null) {
            return false;
        }
        Collection<Property> textures = profile.partialProfile().properties().get("textures");
        for (Property texture : textures) {
            if (EARTH_TEXTURE.equals(texture.value())) {
                return true;
            }
        }
        return false;
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

    private static ResolvableProfile earthProfile() {
        Multimap<String, Property> properties = ArrayListMultimap.create();
        properties.put("textures", new Property("textures", EARTH_TEXTURE));
        GameProfile profile = new GameProfile(EARTH_PROFILE_ID, "Earth", new PropertyMap(properties));
        return ResolvableProfile.createResolved(profile);
    }
}
