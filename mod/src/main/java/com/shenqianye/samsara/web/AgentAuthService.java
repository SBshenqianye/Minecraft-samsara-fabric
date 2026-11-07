package com.shenqianye.samsara.web;

import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Agent 鉴权：管理员 token、scoped token、速率限制。 */
public final class AgentAuthService {
    public static final String ATTR_CONTEXT = "samsara.agent.context";
    private static final AgentAuthService INSTANCE = new AgentAuthService();

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    private AgentAuthService() {
    }

    public static AgentAuthService getInstance() {
        return INSTANCE;
    }

    public AuthContext authenticate(String authorization, String adminToken) {
        String token = WebSecurity.bearerToken(authorization);
        if (token == null || token.isBlank()) {
            return null;
        }
        if (adminToken != null && !adminToken.isBlank()
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                adminToken.getBytes(StandardCharsets.UTF_8))) {
            return new AuthContext("admin", EnumSet.allOf(AgentScope.class), true);
        }
        AgentTokenStore.TokenRecord record = AgentTokenStore.getInstance().findByToken(token);
        if (record == null) {
            return null;
        }
        Set<AgentScope> scopes = record.scopeSet();
        if (scopes.isEmpty()) {
            scopes = EnumSet.of(AgentScope.READ);
        }
        return new AuthContext(record.name, scopes, false);
    }

    public AuthContext context(HttpExchange exchange) {
        Object value = exchange.getAttribute(ATTR_CONTEXT);
        return value instanceof AuthContext context ? context : null;
    }

    /** token 桶限流；返回 false 时调用方应返回 429。 */
    public synchronized boolean allow(AuthContext context, AgentScope scope) {
        if (context == null) {
            return false;
        }
        int limit = limitFor(context, scope);
        long now = System.currentTimeMillis();
        String key = context.actor() + "|" + scope.id();
        Deque<Long> window = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!window.isEmpty() && now - window.peekFirst() > 60_000L) {
            window.removeFirst();
        }
        if (window.size() >= limit) {
            return false;
        }
        window.addLast(now);
        return true;
    }

    public int limit(AgentScope scope) {
        return switch (scope) {
            case READ -> 300;
            case ANNOUNCE -> 60;
            case CONFIG -> 60;
            case OPS -> 30;
            case RCON -> 20;
            case INVENTORY -> 30;
        };
    }

    /**
     * 管理员 token 是本地控制台会话，页面本身会频繁读取状态/地图/列表；
     * 给它更高的窗口，避免正常浏览触发 Agent token 的限流。scoped token 仍用原限额。
     */
    private int limitFor(AuthContext context, AgentScope scope) {
        if (context == null || !context.admin()) {
            return limit(scope);
        }
        return switch (scope) {
            case READ -> 1200;
            case ANNOUNCE -> 120;
            case CONFIG -> 120;
            case OPS -> 60;
            case RCON -> 40;
            case INVENTORY -> 60;
        };
    }

    public Map<String, Object> describe(AuthContext context) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true);
        out.put("actor", context.actor());
        out.put("admin", context.admin());
        out.put("scopes", context.scopes().stream().map(AgentScope::id).sorted().toList());
        out.put("limits", Map.of(
                "readPerMinute", limitFor(context, AgentScope.READ),
                "announcePerMinute", limitFor(context, AgentScope.ANNOUNCE),
                "configPerMinute", limitFor(context, AgentScope.CONFIG),
                "opsPerMinute", limitFor(context, AgentScope.OPS),
                "rconPerMinute", limitFor(context, AgentScope.RCON),
                "inventoryPerMinute", limitFor(context, AgentScope.INVENTORY)));
        out.put("time", System.currentTimeMillis());
        return out;
    }

    public record AuthContext(String actor, Set<AgentScope> scopes, boolean admin) {
        public AuthContext {
            actor = actor == null || actor.isBlank() ? "unknown" : actor;
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }

        public boolean has(AgentScope scope) {
            return admin || scopes.contains(scope);
        }

        public List<String> scopeIds() {
            return scopes.stream().map(AgentScope::id).sorted().toList();
        }
    }
}
