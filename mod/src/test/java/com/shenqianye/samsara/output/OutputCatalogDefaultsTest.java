package com.shenqianye.samsara.output;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认模板必须自洽：required 变量/按钮必须在默认片段里出现。
 *
 * <p>生产回归：2026-11-25 启动日志出现
 * {@code output.yml 的 lottery.quote 非法（缺少必需变量：dayLabel, guess），已回退默认}，
 * 原因是默认片段与 required 标记漂移；随后每次启动都会重写 output.yml，
 * 玩家对模板的自定义会被静默丢弃。
 */
class OutputCatalogDefaultsTest {
    @Test
    void everyDefaultTemplatePassesItsOwnValidation() {
        for (OutputTemplate template : OutputCatalog.all()) {
            Map<String, String> errors = TemplateCodec.validate(template, template.defaultParts());
            assertTrue(errors.isEmpty(),
                    () -> template.key() + " 默认片段非法：" + errors);
        }
    }

    /** 生产事故形态：自定义 lottery.quote 不展示结算日/猜测数字，也必须能通过校验并保留。 */
    @Test
    void quoteWithoutDayLabelAndGuessIsAccepted() {
        OutputTemplate template = OutputCatalog.template("lottery.quote");
        List<TemplatePart> parts = List.of(
                new VariablePart("tickets"), new VariablePart("affordable"),
                new VariablePart("itemLine"), new VariablePart("unitLine"),
                new VariablePart("availableLine"), new VariablePart("costLine"),
                new VariablePart("consumeLine"), new VariablePart("overpayLine"),
                new VariablePart("potLine"), new VariablePart("drawTime"),
                new VariablePart("cutoffTime"));
        assertTrue(TemplateCodec.validate(template, parts).isEmpty());
    }
}
