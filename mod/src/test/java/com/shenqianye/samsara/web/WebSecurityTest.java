package com.shenqianye.samsara.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Web 安全：token 常量时间比较、Bearer 解析、固定路径。 */
class WebSecurityTest {
    @Test
    void bearerTokenRequiresHeaderAndRejectsQueryStyle() {
        assertEquals("abc", WebSecurity.bearerToken("Bearer abc"));
        assertEquals("abc", WebSecurity.bearerToken("bearer abc"));
        assertNull(WebSecurity.bearerToken("abc"));
        assertNull(WebSecurity.bearerToken("Basic abc"));
        assertNull(WebSecurity.bearerToken(null));
        assertFalse(WebSecurity.authorized("abc", "Bearer abd"));
        assertTrue(WebSecurity.authorized("abc", "Bearer abc"));
        assertFalse(WebSecurity.authorized("", "Bearer "));
    }

    @Test
    void staticPathsAreWhitelisted() {
        assertEquals("/web/index.html", WebSecurity.staticResource("/"));
        assertEquals("/web/app.js", WebSecurity.staticResource("/app.js"));
        assertNull(WebSecurity.staticResource("/../config/storage.yml"));
        assertNull(WebSecurity.staticResource("/api/config"));
        assertTrue(WebSecurity.safeApiPath("/api/status"));
        assertFalse(WebSecurity.safeApiPath("/api/../config"));
    }

    @Test
    void bodyLimitIsEnforced() {
        assertTrue(WebSecurity.bodyWithinLimit(0));
        assertTrue(WebSecurity.bodyWithinLimit(WebSecurity.MAX_BODY_BYTES));
        assertFalse(WebSecurity.bodyWithinLimit(WebSecurity.MAX_BODY_BYTES + 1));
        assertFalse(WebSecurity.bodyWithinLimit(-1));
    }
}
