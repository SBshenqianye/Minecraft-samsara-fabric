package com.shenqianye.samsara.permission;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;

import java.util.function.Predicate;

/**
 * Brigadier {@code requires(...)} 的权限谓词工厂。
 *
 * <p>补全与执行共用同一份谓词：{@code CommandDispatcher.getCompletionSuggestions}
 * 只返回 {@code canUse(source)} 为 true 的子节点，因此把完整命令路径登记到叶子上，
 * Tab 补全和聊天栏候选就会跟随 {@code permissions.yml} 的热重载。
 *
 * <p>这里只组合 {@link PermissionChecker} 与原生权限位，不另写权限判断。
 */
public final class PermissionRequirements {
    private PermissionRequirements() {
    }

    /** 仅检查命令路径（控制台按 CONFIG 等级，由 {@link PermissionChecker} 处理）。 */
    public static Predicate<CommandSourceStack> path(String commandPath) {
        return source -> allows(PermissionChecker.getPlayerLevel(source), commandPath);
    }

    /** 原版管理员检查 + 完整命令路径。 */
    public static Predicate<CommandSourceStack> adminAnd(String commandPath) {
        return source -> adminAnd(isAdmin(source),
                PermissionChecker.getPlayerLevel(source), commandPath);
    }

    /** 原版服主检查 + 完整命令路径。 */
    public static Predicate<CommandSourceStack> ownerAnd(String commandPath) {
        return source -> ownerAnd(isOwner(source),
                PermissionChecker.getPlayerLevel(source), commandPath);
    }

    // ---- 纯逻辑入口：与上面的谓词共用同一套判定，便于单测 ----

    public static boolean allows(PermissionLevel playerLevel, String commandPath) {
        return PermissionChecker.check(playerLevel, commandPath);
    }

    /** 测试用：与 {@link #allows(PermissionLevel, String)} 同一套判定，显式传入内存规则。 */
    static boolean allows(PermissionLevel playerLevel, String commandPath, PermissionConfig config) {
        return PermissionChecker.check(playerLevel, commandPath, config);
    }

    public static boolean adminAnd(boolean admin, PermissionLevel playerLevel, String commandPath) {
        return admin && allows(playerLevel, commandPath);
    }

    public static boolean ownerAnd(boolean owner, PermissionLevel playerLevel, String commandPath) {
        return owner && allows(playerLevel, commandPath);
    }

    static boolean adminAnd(boolean admin, PermissionLevel playerLevel, String commandPath,
                            PermissionConfig config) {
        return admin && allows(playerLevel, commandPath, config);
    }

    static boolean ownerAnd(boolean owner, PermissionLevel playerLevel, String commandPath,
                            PermissionConfig config) {
        return owner && allows(playerLevel, commandPath, config);
    }

    public static boolean isAdmin(CommandSourceStack source) {
        return source != null && source.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }

    public static boolean isOwner(CommandSourceStack source) {
        return source != null && source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }
}
