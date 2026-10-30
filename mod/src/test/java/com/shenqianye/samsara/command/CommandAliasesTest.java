package com.shenqianye.samsara.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 短别名注册：空闲时注册，已被占用时让位（ADR-005 / F-62） */
class CommandAliasesTest {

    @Test
    void registersWhenFree() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        assertTrue(CommandAliases.register(dispatcher, "where",
                () -> LiteralArgumentBuilder.literal("where")));
        assertNotNull(dispatcher.getRoot().getChild("where"));
        assertTrue(CommandAliases.isSamsaraRoot("where"), "成功注册的别名应登记为 samsara 根节点");
    }

    @Test
    void yieldsWhenAlreadyOccupied() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        var existing = dispatcher.register(LiteralArgumentBuilder.literal("where"));

        assertFalse(CommandAliases.register(dispatcher, "where",
                () -> LiteralArgumentBuilder.literal("where")),
                "已被其他模组占用时应让位");
        assertSame(existing, dispatcher.getRoot().getChild("where"),
                "不得覆盖已有根节点");
    }

    @Test
    void markRootIsExplicitAndTrimmed() {
        CommandAliases.markRoot(" samsara ");
        assertTrue(CommandAliases.isSamsaraRoot("samsara"));
        assertFalse(CommandAliases.isSamsaraRoot("not-samsara"));
    }

    @Test
    void doesNotBuildNodeWhenOccupied() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.literal("tpa"));
        boolean[] built = {false};

        CommandAliases.register(dispatcher, "tpa", () -> {
            built[0] = true;
            return LiteralArgumentBuilder.literal("tpa");
        });

        assertFalse(built[0], "让位时不应构造别名节点");
    }
}
