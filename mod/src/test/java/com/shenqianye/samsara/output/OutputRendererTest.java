package com.shenqianye.samsara.output;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 渲染核心：颜色码、未知码回退、变量字面插入、按钮预览。 */
class OutputRendererTest {
    @Test
    void knownColorAndFormatCodesAreParsed() {
        OutputTemplate template = new OutputTemplate("test", "测试", "test", List.of(),
                List.of(), List.of());
        List<OutputPreviewFragment> fragments = OutputRenderer.renderRaw(template,
                List.of(new TextPart("&7灰&l粗&r常")), Map.of(),
                OutputRenderer.ButtonPolicy.all());

        String text = OutputRenderer.plainText(fragments);
        assertEquals("灰粗常", text);
        assertTrue(fragments.stream().anyMatch(f -> "gray".equals(f.color())));
        assertTrue(fragments.stream().anyMatch(f -> f.bold()));
        assertTrue(fragments.stream().anyMatch(f -> f.text().equals("常") && !f.bold()));
    }

    @Test
    void unknownColorCodeStaysLiteral() {
        OutputTemplate template = new OutputTemplate("test", "测试", "test", List.of(),
                List.of(), List.of());
        List<OutputPreviewFragment> fragments = OutputRenderer.renderRaw(template,
                List.of(new TextPart("&z未知")), Map.of(),
                OutputRenderer.ButtonPolicy.all());
        assertEquals("&z未知", OutputRenderer.plainText(fragments));
    }

    @Test
    void variableValueIsLiteralNotFormatString() {
        OutputTemplate template = new OutputTemplate("test", "测试", "test",
                List.of(new OutputVariable("x", "X", "55", true, OutputVariableType.TEXT)),
                List.of(), List.of());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("x", "&c{player}");
        List<OutputPreviewFragment> fragments = OutputRenderer.renderRaw(template,
                List.of(new VariablePart("x")), values, OutputRenderer.ButtonPolicy.all());
        assertEquals("&c{player}", OutputRenderer.plainText(fragments));
        assertEquals("variable", fragments.get(0).kind());
        assertFalse(fragments.get(0).clickable());
    }

    @Test
    void handWrittenPlayerBracesStayInText() {
        OutputTemplate template = new OutputTemplate("test", "测试", "test", List.of(),
                List.of(), List.of());
        List<OutputPreviewFragment> fragments = OutputRenderer.renderRaw(template,
                List.of(new TextPart("{player}")), Map.of(),
                OutputRenderer.ButtonPolicy.all());
        assertEquals("{player}", OutputRenderer.plainText(fragments));
    }

    @Test
    void buttonPreviewIsClickableWithFixedCommand() {
        OutputTemplate template = OutputCatalog.template("where.result");
        List<OutputPreviewFragment> fragments = OutputRenderer.renderRaw(template,
                template.defaultParts(), OutputCatalog.sampleValues("where.result"),
                OutputRenderer.ButtonPolicy.all());
        OutputPreviewFragment button = fragments.stream()
                .filter(f -> "button".equals(f.kind())).findFirst().orElseThrow();
        assertTrue(button.clickable());
        assertEquals("/samsara where broadcast", button.clickCommand());
        assertEquals("run", button.clickMode());
        assertTrue(button.text().contains("广播位置"));
    }

    @Test
    void explicitPrefixVariableSuppressesAutomaticFirstPrefix() {
        OutputTemplate template = new OutputTemplate("test", "测试", "test",
                List.of(new OutputVariable("prefix", "前缀", "P: ", false, OutputVariableType.TEXT)),
                List.of(), List.of());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("prefix", "P: ");
        List<OutputPreviewFragment> decorated = OutputRenderer.decorate(
                OutputRenderer.renderRaw(template,
                        List.of(new VariablePart("prefix"), new TextPart("X")),
                        values, OutputRenderer.ButtonPolicy.all()),
                true);
        assertTrue(OutputRenderer.plainText(decorated).startsWith("P: X"));
        assertFalse(OutputRenderer.plainText(decorated).contains("[周目管理] ▸ P:"));
    }

    @Test
    void continuationPrefixIsAddedForSecondLine() {
        OutputTemplate template = new OutputTemplate("test", "测试", "test", List.of(),
                List.of(), List.of());
        List<OutputPreviewFragment> decorated = OutputRenderer.decorate(
                OutputRenderer.renderRaw(template,
                        List.of(new TextPart("a\nb")), Map.of(),
                        OutputRenderer.ButtonPolicy.all()),
                false);
        String plain = OutputRenderer.plainText(decorated);
        assertTrue(plain.startsWith("[周目管理] ▸ a"));
        assertTrue(plain.contains("\n  ▸ b"));
    }
}
