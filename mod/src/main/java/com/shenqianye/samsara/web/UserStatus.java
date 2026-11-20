package com.shenqianye.samsara.web;

/** {@code GET /api/user/status} 的类型化响应；字段名与旧 Map 一致。 */
public record UserStatus(
        boolean ok,
        String scope,
        long generatedAt,
        int online,
        int maxPlayers,
        String version,
        String motd,
        int tickCount
) {
}
