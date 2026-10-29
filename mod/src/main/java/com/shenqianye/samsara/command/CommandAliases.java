package com.shenqianye.samsara.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

import java.util.function.Supplier;

/**
 * 短别名注册（〈命令谦让〉，ADR-005 / F-61、F-62）。
 * <p>
 * samsara 的所有命令都有去掉 {@code /samsara} 前缀的短别名，但**不抢占**：
 * 根节点已被其他模组注册时直接跳过，只保留 {@code /samsara} 全名。
 */
public final class CommandAliases {
    private CommandAliases() {
    }

    /**
     * 尝试注册一个短别名。
     *
     * @param dispatcher 命令调度器
     * @param name       别名根节点名（如 {@code season}/{@code tpa}）
     * @param factory    节点工厂；仅在别名未被占用时调用
     * @return true = 已注册；false = 已存在同名根节点，主动让位
     */
    public static boolean register(CommandDispatcher<CommandSourceStack> dispatcher, String name,
                                   Supplier<LiteralArgumentBuilder<CommandSourceStack>> factory) {
        if (dispatcher.getRoot().getChild(name) != null) {
            return false;
        }
        dispatcher.register(factory.get());
        return true;
    }
}
