package com.shenqianye.samsara.mixin;

import com.shenqianye.samsara.rewind.DiffRecorder;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 记录来源归属：爆炸（TNT/苦力怕/床/重生锚等）期间压入“爆炸”上下文。
 * <p>
 * {@code ServerExplosion.explode()} 内部完成方块破坏与火焰放置；包住整个方法即可把爆炸
 * 造成的方块类型变化记入 diff，而不记录爆炸后的世界模拟。
 */
@Mixin(ServerExplosion.class)
public class ServerExplosionMixin {
    @Inject(method = "explode", at = @At("HEAD"))
    private void samsara$beginExplode(CallbackInfoReturnable<Integer> cir) {
        DiffRecorder.beginEditContext();
    }

    @Inject(method = "explode", at = @At("RETURN"))
    private void samsara$endExplode(CallbackInfoReturnable<Integer> cir) {
        DiffRecorder.endEditContext();
    }
}
