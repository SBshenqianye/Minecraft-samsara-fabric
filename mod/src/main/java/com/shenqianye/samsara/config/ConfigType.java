package com.shenqianye.samsara.config;

/** 配置项类型。决定加载校验、生成 YAML 的写法与 Web 表单控件。 */
public enum ConfigType {
    BOOLEAN("boolean"),
    INT("int"),
    DOUBLE("double"),
    STRING("string"),
    ENUM("enum"),
    STRING_LIST("string-list"),
    INT_LIST("int-list"),
    LEVEL_MAP("level-map"),
    TEMPLATE("template"),
    /** 有序对象列表（轨迹保留曲线：ageDays + keepRatio）。 */
    CURVE("curve");

    private final String id;

    ConfigType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
