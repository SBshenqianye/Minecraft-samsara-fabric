package com.shenqianye.samsara.output;

import java.util.List;

/** 一条可编辑输出的完整定义。 */
public record OutputTemplate(String key, String group, String description,
                             List<OutputVariable> variables, List<OutputButton> buttons,
                             List<TemplatePart> defaultParts) {
    public OutputTemplate {
        key = key == null ? "" : key;
        group = group == null ? "其他" : group;
        description = description == null ? "" : description;
        variables = variables == null ? List.of() : List.copyOf(variables);
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
        defaultParts = defaultParts == null ? List.of() : List.copyOf(defaultParts);
    }

    public OutputVariable variable(String name) {
        for (OutputVariable variable : variables) {
            if (variable.key().equals(name)) {
                return variable;
            }
        }
        return null;
    }

    public OutputButton button(String key) {
        for (OutputButton button : buttons) {
            if (button.key().equals(key)) {
                return button;
            }
        }
        return null;
    }
}
