package com.shenqianye.samsara.web;

/**
 * API 路径 → Agent scope 的纯映射。
 *
 * <p>从 {@code LocalWebServer} 抽出，便于单测覆盖「新增接口有没有挂对权限」；
 * 默认回退到 {@link AgentScope#OPS}，漏写映射时不会误放行成只读。
 */
public final class ApiScope {
    private ApiScope() {
    }

    public static AgentScope required(String method, String path) {
        if ("GET".equals(method)) {
            return AgentScope.READ;
        }
        if (path.startsWith("/api/agent/tokens")) {
            return AgentScope.OPS;
        }
        if (path.startsWith("/api/server/console")) {
            return AgentScope.RCON;
        }
        if (path.startsWith("/api/announcements")) {
            return path.endsWith("/preview") ? AgentScope.READ : AgentScope.ANNOUNCE;
        }
        if (path.startsWith("/api/config")) {
            if (path.equals("/api/config/preview")) {
                return AgentScope.READ;
            }
            return "PUT".equals(method) ? AgentScope.CONFIG : AgentScope.READ;
        }
        if (path.equals("/api/reload")) {
            return AgentScope.CONFIG;
        }
        if (path.startsWith("/api/identity/data")) {
            return AgentScope.CONFIG;
        }
        if (path.startsWith("/api/permissions") || path.startsWith("/api/output")
                || path.startsWith("/api/identity")) {
            return AgentScope.CONFIG;
        }
        if (path.startsWith("/api/audit")) {
            return path.equals("/api/audit/preview") ? AgentScope.READ : AgentScope.OPS;
        }
        if (path.startsWith("/api/inventory")) {
            return "GET".equals(method) ? AgentScope.READ : AgentScope.INVENTORY;
        }
        if (path.startsWith("/api/items")) {
            return "GET".equals(method) || path.endsWith("/describe")
                    ? AgentScope.READ : AgentScope.INVENTORY;
        }
        if (path.startsWith("/api/diff")) {
            return path.equals("/api/diff/view") ? AgentScope.READ : AgentScope.OPS;
        }
        return AgentScope.OPS;
    }
}
