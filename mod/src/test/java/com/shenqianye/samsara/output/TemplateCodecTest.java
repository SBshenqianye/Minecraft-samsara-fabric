package com.shenqianye.samsara.output;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 结构化片段：往返、字段边界、未知变量/required/重复变量。 */
class TemplateCodecTest {
    @Test
    void textVariableButtonRoundTrip() throws Exception {
        List<TemplatePart> parts = List.of(
                new TextPart("坐标 "),
                new VariablePart("x"),
                new TextPart("\n"),
                new ButtonPart("broadcast", "广播")
        );
        List<Map<String, Object>> config = TemplateCodec.toConfig(parts);
        List<TemplatePart> parsed = TemplateCodec.parse(config);

        assertEquals(parts, parsed);
        assertEquals("坐标 ", ((TextPart) parsed.get(0)).text());
        assertEquals("x", ((VariablePart) parsed.get(1)).name());
        assertEquals("broadcast", ((ButtonPart) parsed.get(3)).key());
        assertEquals("广播", ((ButtonPart) parsed.get(3)).label());
    }

    @Test
    void prefixPartRoundTrip() throws Exception {
        List<TemplatePart> parts = List.of(
                new PrefixPart("[测试] "),
                new TextPart("正文")
        );
        List<TemplatePart> parsed = TemplateCodec.parse(TemplateCodec.toConfig(parts));
        assertEquals(parts, parsed);
        assertEquals("[测试] ", ((PrefixPart) parsed.get(0)).text());
    }

    @Test
    void textWithPlayerBracesStaysText() throws Exception {
        List<TemplatePart> parsed = TemplateCodec.parse(List.of(
                Map.of("text", "手写 {player} 不是变量")));
        assertEquals("手写 {player} 不是变量", ((TextPart) parsed.get(0)).text());
        assertTrue(parsed.stream().noneMatch(part -> part instanceof VariablePart));
    }

    @Test
    void newlineSurvivesRoundTrip() throws Exception {
        List<TemplatePart> parsed = TemplateCodec.parse(List.of(
                Map.of("text", "第一行\n第二行")));
        assertEquals("第一行\n第二行", ((TextPart) parsed.get(0)).text());
    }

    @Test
    void unknownVariableAndMissingRequiredAreFieldErrors() {
        OutputTemplate template = OutputCatalog.template("where.result");
        assertNotNull(template);
        List<TemplatePart> parts = List.of(
                new TextPart("坐标 "),
                new VariablePart("x"),
                new VariablePart("nope")
        );
        Map<String, String> errors = TemplateCodec.validate(template, parts);
        assertTrue(errors.containsKey("parts[2].var"));
        assertTrue(errors.get("parts").contains("y"));
    }

    @Test
    void duplicateVariableIsAllowed() {
        OutputTemplate template = OutputCatalog.template("where.result");
        List<TemplatePart> parts = List.of(
                new VariablePart("dimension"),
                new VariablePart("x"), new VariablePart("x"),
                new VariablePart("y"), new VariablePart("z"),
                new VariablePart("chunkX"), new VariablePart("chunkZ"),
                new VariablePart("facing")
        );
        assertTrue(TemplateCodec.validate(template, parts).isEmpty());
    }

    @Test
    void requiredButtonMissingIsRejected() {
        OutputTemplate template = OutputCatalog.template("season.status.header");
        Map<String, String> errors = TemplateCodec.validate(template, List.of(
                new TextPart("没有刷新按钮")));
        assertTrue(errors.values().stream().anyMatch(value -> value.contains("必需按钮")));
    }

    @Test
    void malformedPartIsRejected() {
        assertFalse(TemplateCodec.tryParse(List.of(Map.of("text", "a", "var", "x"))) != null);
        assertFalse(TemplateCodec.tryParse(List.of(Map.of("unknown", "x"))) != null);
        assertFalse(TemplateCodec.tryParse("not-a-list") != null);
    }
}
