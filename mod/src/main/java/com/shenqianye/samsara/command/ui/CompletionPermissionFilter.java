package com.shenqianye.samsara.command.ui;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 补全候选的权限过滤（纯逻辑，便于单测）。
 *
 * <p>只过滤“候选文本正好是一个 literal 子节点”的条目；参数 provider 产出的候选
 * 不在命令树里，原样保留。
 */
public final class CompletionPermissionFilter {
    private CompletionPermissionFilter() {
    }

    public static Suggestions filter(Suggestions suggestions,
                                     CommandNode<CommandSourceStack> parent,
                                     CommandSourceStack source) {
        if (suggestions == null || suggestions.isEmpty() || parent == null) {
            return suggestions;
        }
        List<Suggestion> kept = new ArrayList<>(suggestions.getList().size());
        for (Suggestion suggestion : suggestions.getList()) {
            CommandNode<CommandSourceStack> child = parent.getChild(suggestion.getText());
            if (child instanceof LiteralCommandNode && !child.canUse(source)) {
                continue;
            }
            kept.add(suggestion);
        }
        return kept.size() == suggestions.getList().size()
                ? suggestions
                : new Suggestions(suggestions.getRange(), kept);
    }
}
