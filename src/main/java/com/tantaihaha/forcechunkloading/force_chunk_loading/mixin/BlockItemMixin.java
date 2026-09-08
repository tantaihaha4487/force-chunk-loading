package com.tantaihaha.forcechunkloading.force_chunk_loading.mixin;

import com.tantaihaha.forcechunkloading.force_chunk_loading.ChunkLoadService;
import com.tantaihaha.forcechunkloading.force_chunk_loading.ForceChunkLoading;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Unique
    private static final ThreadLocal<ItemStack> FORCE_CHUNK_LOADING$MARKER_BEFORE_PLACE = new ThreadLocal<>();

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void forceChunkLoading$captureMarker(
            BlockPlaceContext context,
            CallbackInfoReturnable<InteractionResult> callbackInfo
    ) {
        ItemStack stack = context.getItemInHand();
        if (ChunkLoadService.isMarkerItem(stack)) {
            if (context.getLevel() instanceof ServerLevel level) {
                ServerPlayer player = context.getPlayer() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
                boolean permitted = ForceChunkLoading.config().allowPlacement
                        && (player == null || ForceChunkLoading.config().canPlace(player));
                if (permitted && ChunkLoadService.canPlaceMarker(level, context.getClickedPos())) {
                    FORCE_CHUNK_LOADING$MARKER_BEFORE_PLACE.set(stack.copy());
                    return;
                }
                FORCE_CHUNK_LOADING$MARKER_BEFORE_PLACE.remove();
                if (player != null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "Chunk loader placement is disabled, or this chunk already has a loader."
                    ));
                }
                callbackInfo.setReturnValue(InteractionResult.FAIL);
                return;
            }
            FORCE_CHUNK_LOADING$MARKER_BEFORE_PLACE.set(stack.copy());
        } else {
            FORCE_CHUNK_LOADING$MARKER_BEFORE_PLACE.remove();
        }
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void forceChunkLoading$afterPlace(
            BlockPlaceContext context,
            CallbackInfoReturnable<InteractionResult> callbackInfo
    ) {
        ItemStack marker = FORCE_CHUNK_LOADING$MARKER_BEFORE_PLACE.get();
        FORCE_CHUNK_LOADING$MARKER_BEFORE_PLACE.remove();
        if (marker == null || !callbackInfo.getReturnValue().consumesAction()) {
            return;
        }
        if (context.getLevel() instanceof ServerLevel level) {
            ServerPlayer player = context.getPlayer() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
            ChunkLoadService.onPlaced(level, context.getClickedPos(), marker, player);
        }
    }
}
