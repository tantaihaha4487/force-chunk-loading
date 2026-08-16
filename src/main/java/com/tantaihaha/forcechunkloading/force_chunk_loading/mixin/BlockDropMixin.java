package com.tantaihaha.forcechunkloading.force_chunk_loading.mixin;

import com.tantaihaha.forcechunkloading.force_chunk_loading.ChunkLoadService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Block.class)
public abstract class BlockDropMixin {
    @Inject(
            method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void forceChunkLoading$dropMarkerWithoutTool(
            BlockState state,
            LevelAccessor level,
            BlockPos position,
            BlockEntity blockEntity,
            CallbackInfo callbackInfo
    ) {
        if (!(level instanceof ServerLevel serverLevel)
                || !ChunkLoadService.isMarkerBlock(serverLevel, position, state, blockEntity)) {
            return;
        }

        ChunkLoadService.dropMarkerItem(serverLevel, position, null);
        callbackInfo.cancel();
    }

    @Inject(
            method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/entity/BlockEntity;"
                    + "Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void forceChunkLoading$dropMarkerWithTool(
            BlockState state,
            Level level,
            BlockPos position,
            BlockEntity blockEntity,
            Entity breaker,
            ItemStack tool,
            CallbackInfo callbackInfo
    ) {
        if (!(level instanceof ServerLevel serverLevel)
                || !ChunkLoadService.isMarkerBlock(serverLevel, position, state, blockEntity)) {
            return;
        }

        ChunkLoadService.dropMarkerItem(serverLevel, position, breaker);
        callbackInfo.cancel();
    }
}
