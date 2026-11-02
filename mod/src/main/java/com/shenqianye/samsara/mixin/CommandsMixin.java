package com.shenqianye.samsara.mixin;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;
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
            String path = permissionPath(parse);
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
     * 拼出本次输入对应的权限路径。
     *
     * <p>Brigadier 在叶子的 {@code requires(...)} 不通过时不会把该 literal 放进解析结果，
     * 只靠 {@code getNodes()} 会退化成父路径（例如把 {@code season advance} 当成
     * {@code season}），从而把“无权限执行”误判成“未完成指令”。这里把游标处紧邻的
     * literal 子节点补回路径，保持直接执行时的拒绝提示不变。
     */
    private static String permissionPath(ParseResults<CommandSourceStack> parse) {
        List<String> literals = new ArrayList<>();
        CommandNode<CommandSourceStack> deepest = parse.getContext().getRootNode();
        for (var parsed : parse.getContext().getNodes()) {
            deepest = parsed.getNode();
            if (parsed.getNode() instanceof LiteralCommandNode<?> literal) {
                literals.add(literal.getLiteral());
            }
        }
        String remaining = parse.getReader().getRemaining();
        int end = 0;
        while (end < remaining.length() && !Character.isWhitespace(remaining.charAt(end))) {
            end++;
        }
        String token = remaining.substring(0, end);
        if (!token.isEmpty()) {
            CommandNode<CommandSourceStack> child = deepest.getChild(token);
            if (child instanceof LiteralCommandNode<?> literal) {
                literals.add(literal.getLiteral());
            }
        }
        return PermissionCommandPaths.pathOf(literals);
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
