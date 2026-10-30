package com.shenqianye.samsara.output;

/** 变量片段。变量名只能来自后端变量表，Web 不能创建或改名。 */
public record VariablePart(String name) implements TemplatePart {
    public VariablePart {
        name = name == null ? "" : name;
    }

    @Override
    public String type() {
        return "var";
    }
}
