package com.shenqianye.samsara.permission;

/**
 * 权限等级枚举（设计文档扩展 §18 权限系统）。
 * <p>
 * 等级从高到低：DENY > CONFIG > OWNER > OP > USER。
 * 越级判定：玩家等级 ≥ 命令所需等级即允许；DENY 是“关闭权限”，任何来源都不满足。
 */
public enum PermissionLevel {
    /** 关闭权限：高于 config/owner/console，用于模拟热加载关闭 */
    DENY(4),
    /** 配置级：最高，仅通过配置文件或控制台命令设置 */
    CONFIG(3),
    /** 服主级：服务器所有者（permissions.json 中配置） */
    OWNER(2),
    /** OP 级：Minecraft 运算符 */
    OP(1),
    /** 用户级：任何在线玩家 */
    USER(0);

    private final int value;

    PermissionLevel(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /** 玩家等级是否 ≥ 所需等级；DENY 恒不满足（包括控制台）。 */
    public boolean meets(PermissionLevel required) {
        if (required == null || required == DENY || this == DENY) {
            return false;
        }
        return this.value >= required.value;
    }

    /** 从字符串解析（不区分大小写），无效值返回 USER */
    public static PermissionLevel fromString(String s) {
        if (s == null) return USER;
        return switch (s.trim().toLowerCase()) {
            case "deny", "off", "disabled" -> DENY;
            case "config" -> CONFIG;
            case "owner" -> OWNER;
            case "op" -> OP;
            case "user" -> USER;
            default -> USER;
        };
    }
}
