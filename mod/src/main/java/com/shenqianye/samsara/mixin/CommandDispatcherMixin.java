package com.shenqianye.samsara.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.SuggestionContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;
import com.shenqianye.samsara.command.ui.CompletionPermissionFilter;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * 补全候选的权限兜底过滤。
 *
 * <p>Brigadier 1.3.11 的 {@code getCompletionSuggestions} 会遍历父节点的<b>全部</b>子节点，
 * 不检查 {@code canUse(source)}（只有 {@code parseNodes} 过滤），因此只写 {@code .requires(...)}
 * 仍会在 Tab / 聊天栏候选里露出无权限的 literal。这里在返回 future 上按子节点的
 * {@code canUse(source)} 再筛一次；参数候选不受影响。
 */
@Mixin(value = CommandDispatcher.class, remap = false)
public class CommandDispatcherMixin {

    @Inject(method = "getCompletionSuggestions(Lcom/mojang/brigadier/ParseResults;I)Ljava/util/concurrent/CompletableFuture;",
            at = @At("RETURN"), cancellable = true, remap = false)
    private void samsara$filterLiteralSuggestions(ParseResults<CommandSourceStack> parse,
                                                  int cursor,
                                                  CallbackInfoReturnable<CompletableFuture<Suggestions>> cir) {
        CompletableFuture<Suggestions> future = cir.getReturnValue();
        if (future == null || parse == null) {
            return;
        }
        CommandSourceStack source = parse.getContext().getSource();
        SuggestionContext<CommandSourceStack> context =
                parse.getContext().findSuggestionContext(cursor);
        CommandNode<CommandSourceStack> parent = context == null ? null : context.parent;
        if (source == null || parent == null) {
            return;
        }
        cir.setReturnValue(future.thenApply(suggestions ->
                CompletionPermissionFilter.filter(suggestions, parent, source)));
    }
}
