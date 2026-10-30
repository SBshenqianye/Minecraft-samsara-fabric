package com.shenqianye.samsara.permission;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * 权限检查工具（设计文档扩展 §18）。
 * <p>
 * 根据玩家的 OP 状态 + owner 名单判定其权限等级，
 * 与命令所需等级比较。
 */
public class PermissionChecker {

    /**
     * 检查命令源是否有权执行指定命令。
     *
     * @param src         命令源
     * @param commandPath 命令路径（如 "season create"、"claim give"）
     * @return true = 允许
     */
    public static boolean check(CommandSourceStack src, String commandPath) {
        PermissionConfig config = PermissionConfig.getInstance();
        if (config.isDisabled(commandPath)) {
            return false;
        }
        PermissionLevel playerLevel = getPlayerLevel(src);
        PermissionLevel required = config.getRequired(commandPath);
        return playerLevel.meets(required);
    }

    /**
     * 获取命令源的权限等级。
     */
    public static PermissionLevel getPlayerLevel(CommandSourceStack src) {
        // 控制台 = CONFIG
        if (!(src.getEntity() instanceof ServerPlayer)) {
            return PermissionLevel.CONFIG;
        }
        ServerPlayer player = (ServerPlayer) src.getEntity();
        String name = player.getGameProfile().name();

        // owner 名单检查
        if (PermissionConfig.getInstance().isOwner(name)) {
            return PermissionLevel.OWNER;
        }
        // OP 检查（权限等级 ≥ COMMANDS_MODERATOR）
        if (src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)) {
            return PermissionLevel.OP;
        }
        return PermissionLevel.USER;
    }

    /**
     * 设置者的等级是否允许修改目标命令到指定等级。
     *
     * @param setterLevel 设置者的等级
     * @param targetLevel 目标命令当前等级
     * @param newLevel    要设置的新等级
     * @return null = 允许，否则返回错误信息
     */
    public static String canSet(PermissionLevel setterLevel, PermissionLevel targetLevel, PermissionLevel newLevel) {
        // 只能修改 ≤ 自己等级的命令
        if (targetLevel.getValue() > setterLevel.getValue()) {
            return "无法修改：当前命令需要 " + targetLevel + " 级权限，你只有 " + setterLevel + " 级";
        }
        // 只能设置 ≤ 自己等级
        if (newLevel.getValue() > setterLevel.getValue()) {
            return "无法设置为 " + newLevel + " 级：超出你的权限范围（" + setterLevel + "）";
        }
        return null; // 允许
    }
}
