package com.shenqianye.samsara.mixin;

import com.shenqianye.samsara.rewind.DiffRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 差异记录：mixin 到 {@code Level.setBlock}，记录方块类型变化前的状态。
 * <p>
 * 说明：{@code setBlock} 是游戏内所有方块变化（破坏/放置/TNT/红石/流体/作物）的统一入口。
 * 在 HEAD 处记录——此时 {@code getBlockState(pos)} 仍是「变化前」状态。是否记录由
 * {@link DiffRecorder} 判断（仅 block 类型变化，过滤红石/流体/作物等噪声）。
 * <p>
 * 26.3 起 {@code LevelAccessor} 的 3 参数 {@code setBlock} 变成 default 方法，实际实现是
 * {@code Level.setBlock(BlockPos, BlockState, int flags, int recursionLeft)}；这里注入 4 参数版本，
 * 才能覆盖所有调用路径。
 */
@Mixin(Level.class)
public class ServerLevelMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void samsara$recordBlockChange(BlockPos pos, BlockState newState, int flags, int recursionLeft,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockState before = serverLevel.getBlockState(pos);
        DiffRecorder.record(serverLevel, pos, before, newState);
    }
}
