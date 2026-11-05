package com.shenqianye.samsara.web;

import com.sun.net.httpserver.HttpExchange;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写请求幂等缓存：客户端带 {@code Idempotency-Key} 时，同一 actor + method + path + key
 * 在 10 分钟内重复提交会直接返回首次响应，避免网络重试造成重复公告/重复保存。
 */
public final class IdempotencyService {
    public static final String HEADER = "Idempotency-Key";
    public static final String ATTR_KEY = "samsara.idempotency.key";
    public static final String ATTR_ACTOR = "samsara.idempotency.actor";
    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_ENTRIES = 1000;

    private static final IdempotencyService INSTANCE = new IdempotencyService();

    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private IdempotencyService() {
    }

    public static IdempotencyService getInstance() {
        return INSTANCE;
    }

    /** 记录请求上下文；返回 false 表示请求没有幂等键。 */
    public boolean attach(HttpExchange exchange, String actor) {
        String method = exchange.getRequestMethod();
        if (!"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method)) {
            return false;
        }
        String key = exchange.getRequestHeaders().getFirst(HEADER);
        if (key == null || key.isBlank() || key.length() > 128) {
            return false;
        }
        exchange.setAttribute(ATTR_KEY, key);
        exchange.setAttribute(ATTR_ACTOR, actor == null ? "unknown" : actor);
        return true;
    }

    public synchronized Cached replay(HttpExchange exchange) {
        String key = attr(exchange, ATTR_KEY);
        String actor = attr(exchange, ATTR_ACTOR);
        if (key == null || actor == null) {
            return null;
        }
        String cacheKey = cacheKey(exchange, actor, key);
        Cached cached = cache.get(cacheKey);
        if (cached == null) {
            return null;
        }
        if (System.currentTimeMillis() - cached.createdAt() > TTL_MS) {
            cache.remove(cacheKey);
            return null;
        }
        return cached;
    }

    public synchronized void store(HttpExchange exchange, int status, String body) {
        if (status >= 500) {
            return;
        }
        String key = attr(exchange, ATTR_KEY);
        String actor = attr(exchange, ATTR_ACTOR);
        if (key == null || actor == null || body == null) {
            return;
        }
        cache.put(cacheKey(exchange, actor, key),
                new Cached(status, body, System.currentTimeMillis()));
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > TTL_MS);
    }

    private static String cacheKey(HttpExchange exchange, String actor, String key) {
        return actor + '|' + exchange.getRequestMethod() + '|'
                + exchange.getRequestURI().getPath() + '|' + key;
    }

    private static String attr(HttpExchange exchange, String name) {
        Object value = exchange.getAttribute(name);
        return value == null ? null : String.valueOf(value);
    }

    public record Cached(int status, String body, long createdAt) {
    }
}
