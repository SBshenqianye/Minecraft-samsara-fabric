package com.shenqianye.samsara.mixin;

import com.shenqianye.samsara.command.IncompleteCommandHandler;
import com.shenqianye.samsara.command.ui.CompletionAction;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 补全翻页按钮的服务端入口。
 *
 * <p>翻页使用 {@code ClickEvent.Custom}，由原版客户端发送
 * {@link ServerboundCustomClickActionPacket}；这里只识别
 * {@link CompletionAction#ID}，其余自定义动作完全交给原版/其他模组。
 *
 * <p>不注册隐藏命令：隐藏命令会出现在 Tab 补全和帮助列表里。
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class CompletionPageMixin {
    @Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
    private void samsara$handleCompletionPage(ServerboundCustomClickActionPacket packet,
                                              CallbackInfo ci) {
        if (packet == null || !CompletionAction.ID.equals(packet.id())) {
            return;
        }
        if (!((Object) this instanceof ServerGamePacketListenerImpl game)) {
            return;
        }
        var player = game.player;
        if (player == null) {
            return;
        }
        var server = player.level().getServer();
        if (server == null) {
            return;
        }
        // 网络线程只负责转交；候选计算与发送都在主线程完成。
        server.execute(() -> IncompleteCommandHandler.handlePage(
                player, packet.payload().orElse(null)));
        ci.cancel();
    }
}
