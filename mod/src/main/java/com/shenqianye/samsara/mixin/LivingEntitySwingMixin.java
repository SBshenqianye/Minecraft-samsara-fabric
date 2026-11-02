package com.shenqianye.samsara.mixin;

import com.shenqianye.samsara.track.TrackRecorder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 服务端侧挥动兜底：Carpet/PuppetPlayers 等直接调用 {@code LivingEntity.swing}，
 * 不经过客户端 {@code ServerboundPunchPacket}。只记录真人玩家，人偶自身不记录。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {
    @Inject(method = "swing", at = @At("HEAD"))
    private void samsara$recordSwing(InteractionHand hand, SwingAnimation animation,
                                     boolean updateSelf, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer player) {
            int animationId = animation == null ? 0 : animation.type().getId();
            TrackRecorder.getInstance().recordSwing(player, hand, animationId);
        }
    }
}
