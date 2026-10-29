package com.shenqianye.samsara.command;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TPA 消息里的可点击按钮：收件人只拿 [同意]/[拒绝]，发起者只拿 [取消请求]，
 * 两条消息互不串台（避免把按钮广播给无关玩家）。
 */
class TpaCommandTest {

    @Test
    void targetSeesAcceptAndDenyButtons() {
        MutableComponent line = TpaCommand.targetRequestLine("Bot", false, "60 秒");

        assertTrue(line.getString().contains("Bot 请求传送到你这里。"));
        assertTrue(line.getString().contains("[同意]"));
        assertTrue(line.getString().contains("[拒绝]"));
        assertTrue(hasClick(line, "/samsara tpaccept"), "同意按钮应点击执行 tpaccept");
        assertTrue(hasClick(line, "/samsara tpdeny"), "拒绝按钮应点击执行 tpdeny");
        assertFalse(line.getString().contains("[取消请求]"), "收件人不应看到发起者的取消按钮");
    }

    @Test
    void tpahereWordingIsFromTargetPerspective() {
        MutableComponent line = TpaCommand.targetRequestLine("Bot", true, "60 秒");
        assertTrue(line.getString().contains("Bot 请求你传送到他那里。"));
        assertTrue(hasClick(line, "/samsara tpaccept"));
    }

    @Test
    void senderOnlySeesCancelButton() {
        MutableComponent line = TpaCommand.senderRequestLine("Bot2", false, "60 秒");

        assertTrue(line.getString().contains("已向 Bot2 发送传送请求"));
        assertTrue(line.getString().contains("[取消请求]"));
        assertTrue(hasClick(line, "/samsara tpcancel"));
        assertFalse(line.getString().contains("[同意]"), "发起者不应看到收件人的同意按钮");
        assertFalse(line.getString().contains("[拒绝]"));
    }

    @Test
    void senderTpahereWording() {
        MutableComponent line = TpaCommand.senderRequestLine("Bot2", true, "60 秒");
        assertTrue(line.getString().contains("已请求 Bot2 传送到你这里"));
    }

    @Test
    void hintLineIsPlainText() {
        MutableComponent hint = TpaCommand.targetHintLine("60 秒");
        assertNull(hint.getStyle().getClickEvent());
        assertFalse(hint.getString().contains("[同意]"));
        assertTrue(hint.getString().contains("60 秒"));
    }

    /** 递归查找带指定 RunCommand 的可点击子组件。 */
    private static boolean hasClick(Component component, String command) {
        if (component == null) {
            return false;
        }
        var click = component.getStyle().getClickEvent();
        if (click instanceof ClickEvent.RunCommand run && command.equals(run.command())) {
            return true;
        }
        for (Component child : component.getSiblings()) {
            if (hasClick(child, command)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void buttonsCarryHoverText() {
        MutableComponent line = TpaCommand.targetRequestLine("Bot", false, "60 秒");
        boolean found = false;
        for (Component child : line.getSiblings()) {
            var hover = child.getStyle().getHoverEvent();
            if (hover instanceof net.minecraft.network.chat.HoverEvent.ShowText show
                    && child.getString().contains("同意")) {
                assertNotNull(show.value().getString());
                found = true;
            }
        }
        assertTrue(found, "同意按钮应有 hover 说明");
    }
}
