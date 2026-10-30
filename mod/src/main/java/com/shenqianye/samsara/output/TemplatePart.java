package com.shenqianye.samsara.output;

/** 输出模板的有序片段：普通文本、变量、按钮插槽。 */
public sealed interface TemplatePart permits TextPart, PrefixPart, VariablePart, ButtonPart {
    String type();
}
