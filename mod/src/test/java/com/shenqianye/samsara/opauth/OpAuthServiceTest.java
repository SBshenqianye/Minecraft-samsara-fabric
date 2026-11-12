package com.shenqianye.samsara.opauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OP 口令只管权限：认证命令始终放行；密码答错不踢人、不封禁、可无限重试。
 * 这些是纯静态判定，不依赖 Minecraft 运行时。
 */
class OpAuthServiceTest {

    @Test
    void authCommandsAlwaysAllowedWhilePending() {
        assertTrue(OpAuthService.isAuthCommand("/login 12345678"));
        assertTrue(OpAuthService.isAuthCommand("l 12345678"));
        assertTrue(OpAuthService.isAuthCommand("/register CODE pw pw"));
        assertTrue(OpAuthService.isAuthCommand("reg CODE pw pw"));
        assertTrue(OpAuthService.isAuthCommand("/help"));
        assertTrue(OpAuthService.isAuthCommand("/samsara"));
        assertTrue(OpAuthService.isAuthCommand("/samsara login pw"));
        assertTrue(OpAuthService.isAuthCommand("/samsara register CODE pw pw"));
        assertTrue(OpAuthService.isAuthCommand("/samsara help"));
    }

    @Test
    void ordinaryCommandsAreNotBlockedByTheAuthGate() {
        // pending 状态下普通游玩命令由 CommandsMixin 的
        // requiresElevatedPermission 判定放行，不再被 isAuthCommand 一刀切拦下。
        assertFalse(OpAuthService.isAuthCommand("/home"));
        assertFalse(OpAuthService.isAuthCommand("/tpa Bob"));
        assertFalse(OpAuthService.isAuthCommand("/gamemode creative"));
        assertFalse(OpAuthService.isAuthCommand(""));
        assertFalse(OpAuthService.isAuthCommand(null));
    }
}
