package com.shenqianye.samsara.mixin;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.shenqianye.samsara.command.IncompleteCommandHandler;
import com.shenqianye.samsara.permission.PermissionChecker;
import com.shenqianye.samsara.permission.PermissionCommandPaths;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

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
        if (parse != null && IncompleteCommandHandler.isSamsaraCommand(command)) {
            CommandSourceStack source = parse.getContext().getSource();
            List<String> literals = new ArrayList<>();
            for (var parsed : parse.getContext().getNodes()) {
                if (parsed.getNode() instanceof LiteralCommandNode<?> literal) {
                    literals.add(literal.getLiteral());
                }
            }
            String path = PermissionCommandPaths.pathOf(literals);
            if (!PermissionChecker.check(source, path.isBlank() ? "*" : path)) {
                source.sendFailure(Component.literal("samsara 已被关闭或你没有权限执行"));
                ci.cancel();
                return;
            }
        }
        if (IncompleteCommandHandler.tryHandle(parse, command)) {
            ci.cancel();
        }
    }

    /**
     * 节点自身有执行器时，执行完补一个 {@code [展开子命令]}。
     *
     * <p>不能靠尾随空格区分“执行/展开”：原版客户端提交前会 trim，服务端收到的命令
     * 没有尾随空格。这里用执行结果里的按钮表达“还想看子命令”。
     */
    @Inject(method = "performCommand", at = @At("TAIL"))
    private void samsara$appendExpandHint(ParseResults<CommandSourceStack> parse,
                                          String command,
                                          CallbackInfo ci) {
        IncompleteCommandHandler.appendExpandHint(parse, command);
    }
}
