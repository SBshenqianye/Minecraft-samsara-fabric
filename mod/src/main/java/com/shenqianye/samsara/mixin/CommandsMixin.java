package com.shenqianye.samsara.mixin;

import com.mojang.brigadier.ParseResults;
import com.shenqianye.samsara.command.IncompleteCommandHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 未完成指令的聊天栏候选入口。
 *
 * <p>原版 {@link Commands#performCommand(ParseResults, String)} 在解析失败时把错误
 * 作为一行文字发回；这里在方法头先尝试 {@link IncompleteCommandHandler}：
 * 命中（samsara 根节点 + 解析不完整 + 有候选）就取消原方法，只发可点击候选。
 * 其他情况（其他模组命令、执行期异常、无候选）完全保持原版行为。
 */
@Mixin(Commands.class)
public class CommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void samsara$chatCompletionForIncompleteCommand(ParseResults<CommandSourceStack> parse,
                                                            String command,
                                                            CallbackInfo ci) {
        if (IncompleteCommandHandler.tryHandle(parse, command)) {
            ci.cancel();
        }
    }
}
