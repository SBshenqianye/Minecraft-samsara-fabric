package com.shenqianye.samsara.permission;

/**
 * 权限等级枚举（设计文档扩展 §18 权限系统）。
 * <p>
 * 等级从高到低：CONFIG > OWNER > OP > USER。
 * 越级判定：玩家等级 ≥ 命令所需等级即允许。
 */
public enum PermissionLevel {
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

    /** 玩家等级是否 ≥ 所需等级 */
    public boolean meets(PermissionLevel required) {
        return this.value >= required.value;
    }

    /** 从字符串解析（不区分大小写），无效值返回 USER */
    public static PermissionLevel fromString(String s) {
        if (s == null) return USER;
        return switch (s.trim().toLowerCase()) {
            case "config" -> CONFIG;
            case "owner" -> OWNER;
            case "op" -> OP;
            case "user" -> USER;
            default -> USER;
        };
    }
}
