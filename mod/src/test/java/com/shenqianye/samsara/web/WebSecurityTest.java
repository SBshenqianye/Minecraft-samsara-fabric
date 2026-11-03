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
        assertEquals("/web/user.html", WebSecurity.staticResource("/user"));
        assertEquals("/web/user.js", WebSecurity.staticResource("/user.js"));
        assertEquals("/web/banners.js", WebSecurity.staticResource("/banners.js"));
        assertEquals("/web/map.js", WebSecurity.staticResource("/map.js"));
        assertEquals("/web/world-stack.js", WebSecurity.staticResource("/world-stack.js"));
        assertNull(WebSecurity.staticResource("/../config/storage.yml"));
        assertNull(WebSecurity.staticResource("/api/config"));
        assertTrue(WebSecurity.safeApiPath("/api/status"));
        assertTrue(WebSecurity.safeApiPath("/api/map"));
        assertTrue(WebSecurity.safeApiPath("/api/map/tile"));
        assertTrue(WebSecurity.safeApiPath("/api/user/status"));
        assertTrue(WebSecurity.safeApiPath("/api/user/worlds"));
        assertTrue(WebSecurity.safeApiPath("/api/user/map"));
        assertTrue(WebSecurity.safeApiPath("/api/user/map/players"));
        assertTrue(WebSecurity.safeApiPath("/api/user/map/tile"));
        assertTrue(WebSecurity.safeApiPath("/api/user/worlds/icon"));
        assertTrue(WebSecurity.safeApiPath("/api/world-stack/icon"));
        assertTrue(WebSecurity.safeApiPath("/api/world-stack/icon/delete"));
        assertTrue(WebSecurity.safeApiPath("/api/diff/chunk"));
        assertTrue(WebSecurity.safeApiPath("/api/diff/chunk/restore"));
        assertTrue(WebSecurity.safeApiPath("/api/diff/view"));
        assertTrue(WebSecurity.safeApiPath("/api/config/retention-estimate"));
        assertTrue(WebSecurity.safeApiPath("/api/world-stack"));
        assertTrue(WebSecurity.safeApiPath("/api/world-stack/import/preview"));
        assertTrue(WebSecurity.safeApiPath("/api/world-stack/jobs/imp-1"));
        assertFalse(WebSecurity.safeApiPath("/api/../config"));
    }

    @Test
    void bodyLimitIsEnforced() {
        assertTrue(WebSecurity.bodyWithinLimit(0));
        assertTrue(WebSecurity.bodyWithinLimit(WebSecurity.MAX_BODY_BYTES));
        assertFalse(WebSecurity.bodyWithinLimit(WebSecurity.MAX_BODY_BYTES + 1));
        assertTrue(WebSecurity.bodyWithinLimit(WebSecurity.MAX_ICON_BODY_BYTES,
                WebSecurity.MAX_ICON_BODY_BYTES));
        assertFalse(WebSecurity.bodyWithinLimit(WebSecurity.MAX_ICON_BODY_BYTES + 1,
                WebSecurity.MAX_ICON_BODY_BYTES));
        assertFalse(WebSecurity.bodyWithinLimit(-1));
    }
}
