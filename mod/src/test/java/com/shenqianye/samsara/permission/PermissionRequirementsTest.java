package com.shenqianye.samsara.permission;

import com.shenqianye.samsara.command.TpaCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 补全谓词：路径等级、关闭级联、管理员/服主组合与别名同一 key。 */
class PermissionRequirementsTest {

    private static PermissionConfig config() {
        return PermissionConfig.forTests(Map.of(), Set.of());
    }

    private static PermissionConfig configDisabled(String... paths) {
        return PermissionConfig.forTests(Map.of(), Set.of(paths));
    }

    @Test
    void pathAllowsOnlyWhenLevelMeetsRequirement() {
        PermissionConfig config = config();
        assertTrue(PermissionRequirements.allows(PermissionLevel.USER, "season advance", config),
                "默认 user 的叶子对普通玩家可见");
        assertFalse(PermissionRequirements.allows(PermissionLevel.USER, "season create", config),
                "默认 owner 的叶子不对普通玩家显示");
        assertTrue(PermissionRequirements.allows(PermissionLevel.OWNER, "season create", config));
        assertTrue(PermissionRequirements.allows(PermissionLevel.CONFIG, "season create", config),
                "控制台/配置级始终满足最高等级");
    }

    @Test
    void disabledPathHidesCandidateEvenForOwner() {
        PermissionConfig config = configDisabled("season advance");
        assertFalse(PermissionRequirements.allows(PermissionLevel.OWNER, "season advance", config),
                "disabled 优先于等级");
        assertFalse(PermissionRequirements.allows(PermissionLevel.CONFIG, "season advance", config),
                "控制台也不能绕过 disabled");
    }

    @Test
    void ancestorDisabledCascadesToCompletionPath() {
        PermissionConfig config = configDisabled("season");
        assertFalse(PermissionRequirements.allows(PermissionLevel.OWNER, "season status", config));
        assertFalse(PermissionRequirements.allows(PermissionLevel.OWNER, "season advance", config));
    }

    @Test
    void adminCombinationRequiresBothAdminAndPermissionPath() {
        PermissionConfig config = config();
        assertFalse(PermissionRequirements.adminAnd(false, PermissionLevel.OWNER,
                "season create", config), "不是管理员时即使等级足够也不显示");
        assertFalse(PermissionRequirements.adminAnd(true, PermissionLevel.USER,
                "season create", config), "管理员身份不能替代 owner 路径等级");
        assertTrue(PermissionRequirements.adminAnd(true, PermissionLevel.OWNER,
                "season create", config));
    }

    @Test
    void ownerCombinationRequiresBothOwnerAndPermissionPath() {
        PermissionConfig config = config();
        assertFalse(PermissionRequirements.ownerAnd(false, PermissionLevel.CONFIG, "debug", config));
        assertFalse(PermissionRequirements.ownerAnd(true, PermissionLevel.OP, "debug", config));
        assertTrue(PermissionRequirements.ownerAnd(true, PermissionLevel.OWNER, "debug", config));
    }

    @Test
    void denyBlocksEveryLevelIncludingConfig() {
        PermissionConfig config = PermissionConfig.forTests(
                Map.of("debug", PermissionLevel.DENY), Set.of());
        assertFalse(PermissionRequirements.allows(PermissionLevel.USER, "debug", config));
        assertFalse(PermissionRequirements.allows(PermissionLevel.OP, "debug", config));
        assertFalse(PermissionRequirements.allows(PermissionLevel.OWNER, "debug", config));
        assertFalse(PermissionRequirements.allows(PermissionLevel.CONFIG, "debug", config),
                "DENY 高于 config/控制台");
        assertFalse(PermissionRequirements.allows(PermissionLevel.CONFIG, "debug rewind", config),
                "DENY 沿路径向下级联");
    }

    @Test
    void denyParsingAndRecoveryPaths() {
        assertEquals(PermissionLevel.DENY, PermissionLevel.fromString("deny"));
        assertEquals(PermissionLevel.DENY, PermissionLevel.fromString("off"));
        assertEquals(PermissionLevel.DENY, PermissionLevel.fromString("disabled"));
        assertFalse(PermissionLevel.CONFIG.meets(PermissionLevel.DENY));
        assertTrue(PermissionConfig.isRecoveryPath("perm"));
        assertTrue(PermissionConfig.isRecoveryPath("perm set"));
        assertTrue(PermissionConfig.isRecoveryPath("debug config"));
        assertTrue(PermissionConfig.isRecoveryPath("debug reload"));
        assertFalse(PermissionConfig.isRecoveryPath("season advance"));
    }

    @Test
    void aliasUsesMainCommandPermissionKey() {
        assertEquals("tpaccept", PermissionCommandPaths.canonicalPath("tpyes"));
        assertEquals("tpdeny", PermissionCommandPaths.canonicalPath("tpno"));
        assertEquals("tpaccept", PermissionCommandPaths.pathOf(List.of("tpyes")));
        assertEquals("tpaccept", TpaCommand.permissionPath("tpyes"));
        assertEquals("tpdeny", TpaCommand.permissionPath("tpno"));
    }

    @Test
    void disablingMainPathAlsoHidesAlias() {
        PermissionConfig config = configDisabled("tpaccept");
        assertFalse(PermissionRequirements.allows(PermissionLevel.USER,
                PermissionCommandPaths.canonicalPath("tpyes"), config));
        assertTrue(PermissionRequirements.allows(PermissionLevel.USER,
                PermissionCommandPaths.canonicalPath("tpa"), config));
    }
}
