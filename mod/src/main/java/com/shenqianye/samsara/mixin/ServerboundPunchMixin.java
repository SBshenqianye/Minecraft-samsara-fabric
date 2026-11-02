package com.shenqianye.samsara.mixin;

import com.shenqianye.samsara.track.TrackRecorder;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 的“挥手/左键”包是 {@link ServerboundPunchPacket}（旧名 ServerboundSwingPacket）。
 * 在服务端处理入口记录 SWING，时间最准；实体/方块攻击另有 Fabric 回调兜底。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerboundPunchMixin {
    @Inject(method = "handlePunch", at = @At("HEAD"))
    private void samsara$recordPunch(ServerboundPunchPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (self.player != null) {
            TrackRecorder.getInstance().recordSwing(self.player, InteractionHand.MAIN_HAND, 0);
        }
    }
}
