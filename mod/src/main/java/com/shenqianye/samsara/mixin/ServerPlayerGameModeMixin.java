package com.shenqianye.samsara.mixin;

import com.shenqianye.samsara.rewind.DiffRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 记录来源归属：玩家破坏/放置（含右键使用物品）期间压入“玩家操作”上下文，
 * {@link com.shenqianye.samsara.mixin.ServerLevelMixin} 只在上下文内记录方块变化。
 * <p>
 * 覆盖的入口：
 * <ul>
 *   <li>{@code destroyBlock}：生存/创造破坏、延迟破坏；</li>
 *   <li>{@code useItemOn}：放置方块、使用骨粉/锄/锹/打火石/水桶等右键行为；</li>
 *   <li>{@code useItem}：使用物品（水桶、打火石等不经过 useItemOn 的路径）。</li>
 * </ul>
 * 红石/作物/流体扩散/树叶腐烂等世界模拟不经过这些方法，因此不会被记录。
 */
@Mixin(ServerPlayerGameMode.class)
public class ServerPlayerGameModeMixin {
    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void samsara$beginDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        DiffRecorder.beginEditContext();
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void samsara$endDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        DiffRecorder.endEditContext();
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void samsara$beginUseItem(ServerPlayer player, Level level, ItemStack stack,
                                      InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        DiffRecorder.beginEditContext();
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void samsara$endUseItem(ServerPlayer player, Level level, ItemStack stack,
                                    InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        DiffRecorder.endEditContext();
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void samsara$beginUseItemOn(ServerPlayer player, Level level, ItemStack stack,
                                        InteractionHand hand, BlockHitResult hit,
                                        CallbackInfoReturnable<InteractionResult> cir) {
        DiffRecorder.beginEditContext();
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void samsara$endUseItemOn(ServerPlayer player, Level level, ItemStack stack,
                                      InteractionHand hand, BlockHitResult hit,
                                      CallbackInfoReturnable<InteractionResult> cir) {
        DiffRecorder.endEditContext();
    }
}
