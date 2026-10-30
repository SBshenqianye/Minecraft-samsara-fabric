package com.shenqianye.samsara.command.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 可点击 UI 框架：RunCommand / SuggestCommand / hover / 颜色 / 括号与命令规范化。 */
class ChatUiTest {

    @Test
    void runUsesRunCommandAndAqua() {
        MutableComponent component = ChatUi.run("同意", "samsara tpaccept", "点击同意传送");

        Style style = component.getStyle();
        ClickEvent click = style.getClickEvent();
        assertInstanceOf(ClickEvent.RunCommand.class, click, "run 必须是点击即执行");
        assertEquals("/samsara tpaccept", ((ClickEvent.RunCommand) click).command(),
                "命令缺少前导 / 时应自动补上");
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.AQUA), style.getColor());
        assertEquals("[同意]", component.getString(), "按钮统一带方括号");
    }

    @Test
    void suggestUsesSuggestCommandAndYellow() {
        MutableComponent component = ChatUi.suggest("下一时段",
                "/samsara track view Bot 2026-10-28T21:20", "点击填入，不执行");

        Style style = component.getStyle();
        ClickEvent click = style.getClickEvent();
        assertInstanceOf(ClickEvent.SuggestCommand.class, click, "suggest 只能填入，不能执行");
        assertEquals("/samsara track view Bot 2026-10-28T21:20",
                ((ClickEvent.SuggestCommand) click).command());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.YELLOW), style.getColor());
        assertEquals("[下一时段]", component.getString());
    }

    @Test
    void hoverTextIsPreserved() {
        MutableComponent component = ChatUi.run("停止", "/samsara track view stop", "停止回看并恢复状态");

        HoverEvent hover = component.getStyle().getHoverEvent();
        assertInstanceOf(HoverEvent.ShowText.class, hover);
        assertEquals("停止回看并恢复状态",
                ((HoverEvent.ShowText) hover).value().getString());
    }

    @Test
    void commandWithLeadingSlashIsNotDoubled() {
        MutableComponent component = ChatUi.run("版本", "/samsara version", "查看版本");
        assertEquals("/samsara version",
                ((ClickEvent.RunCommand) component.getStyle().getClickEvent()).command());
        assertEquals("/", ChatUi.normalizeCommand(null));
        assertEquals("/x", ChatUi.normalizeCommand("x"));
        assertEquals("/x", ChatUi.normalizeCommand(" /x "));
    }

    @Test
    void suggestPreservesTrailingSpaceForDrillDown() {
        MutableComponent component = ChatUi.suggest("周目", "samsara season ", "继续输入");
        assertEquals("/samsara season ",
                ((ClickEvent.SuggestCommand) component.getStyle().getClickEvent()).command(),
                "补全按钮需要保留尾随空格，normalizeCommand 的 trim 不能吃掉它");
    }

    @Test
    void alreadyBracketedLabelIsNotWrappedTwice() {
        assertEquals("[停止]", ChatUi.run("[停止]", "/stop", "停止").getString());
    }

    @Test
    void textVariantHasNoBrackets() {
        MutableComponent entry = ChatUi.suggestText("  ▸ cycle0 / 2026-10-28 21:20",
                "/samsara track view Bot 2026-10-28T21:20", "点击填入");
        assertTrue(entry.getString().startsWith("  ▸ cycle0"), "整行列表不应被额外加括号");
        assertInstanceOf(ClickEvent.SuggestCommand.class, entry.getStyle().getClickEvent());
    }

    @Test
    void disabledButtonHasNoClickEventAndIsGray() {
        MutableComponent disabled = ChatUi.runIf(false, "开始周目", "/samsara season start", "危险");
        assertNull(disabled.getStyle().getClickEvent(), "无权限时不应保留可点击事件");
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.DARK_GRAY), disabled.getStyle().getColor());
        assertEquals("[开始周目]", disabled.getString());

        MutableComponent allowed = ChatUi.runIf(true, "开始周目", "/samsara season start", "危险");
        assertInstanceOf(ClickEvent.RunCommand.class, allowed.getStyle().getClickEvent());
    }

    @Test
    void lineJoinsPartsWithSingleSpaces() {
        MutableComponent line = ChatUi.line(
                ChatUi.text("[回看控制]", ChatFormatting.GRAY),
                ChatUi.run("暂停", "/samsara track view pause", "暂停"),
                ChatUi.run("继续", "/samsara track view resume", "继续"));
        assertEquals("[回看控制] [暂停] [继续]", line.getString());
    }

    @Test
    void plainTextUsesRequestedColor() {
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED),
                ChatUi.text("损坏", ChatFormatting.RED).getStyle().getColor());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE),
                ChatUi.text("普通").getStyle().getColor());
    }
}
