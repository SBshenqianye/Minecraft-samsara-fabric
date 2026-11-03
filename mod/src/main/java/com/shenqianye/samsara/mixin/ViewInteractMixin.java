package com.shenqianye.samsara.mixin;

import com.shenqianye.samsara.track.view.ViewManager;
import com.shenqianye.samsara.track.view.ViewMannequin;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 旁观者右键回看人偶：原版会先做 3 格交互距离/旁观判定，跟随观察点通常够不到；
 * 在包处理入口直接识别“本人会话的人偶”，打开只读背包并取消原包。
 * <p>
 * 左键切主视角仍走原版 {@code ServerboundSpectatorActionPacket}，不受影响。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ViewInteractMixin {
    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void samsara$inspectViewMannequin(ServerboundInteractPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        ServerPlayer player = self.player;
        if (player == null || !player.isSpectator()) {
            return;
        }
        Entity target = player.level() instanceof net.minecraft.server.level.ServerLevel level
                ? level.getEntityOrPart(packet.entityId()) : null;
        if (target instanceof ViewMannequin mannequin
                && ViewManager.getInstance().isViewing(player.getUUID())) {
            ViewManager.getInstance().inspectByRightClick(player, mannequin);
            ci.cancel();
        }
    }
}
