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
        assertEquals("/web/vue/index.html", WebSecurity.staticResource("/"));
        assertEquals("/web/vue/index.html", WebSecurity.staticResource("/user"));
        assertEquals("/web/vue/index.html", WebSecurity.staticResource("/containers"));
        assertEquals("/web/vue/index.html", WebSecurity.staticResource("/config-editor"));
        assertEquals("/web/vue/index.html", WebSecurity.staticResource("/map"));
        assertEquals("/web/vue/index.html", WebSecurity.staticResource("/server/console"));
        // 旧 Web 背包链接仍需返回 SPA，由前端路由重定向到玩家背包。
        assertEquals("/web/vue/index.html", WebSecurity.staticResource("/samsara/sky/web-inventory"));
        assertEquals("/web/vue/assets/index-abc.js", WebSecurity.staticResource("/vue/assets/index-abc.js"));
        assertNull(WebSecurity.staticResource("/vue/../app.js"));
        assertEquals("/web/openapi.json", WebSecurity.staticResource("/openapi.json"));
        assertEquals("/web/vue/index.html", WebSecurity.staticResource("/lottery"));
        // 旧静态页面壳与脚本已迁入 Vite 源码，不再从 /web 提供。
        assertNull(WebSecurity.staticResource("/legacy.html"));
        assertNull(WebSecurity.staticResource("/legacy-user.html"));
        assertNull(WebSecurity.staticResource("/user.html"));
        assertNull(WebSecurity.staticResource("/app.js"));
        assertNull(WebSecurity.staticResource("/style.css"));
        assertNull(WebSecurity.staticResource("/page-kit.js"));
        assertNull(WebSecurity.staticResource("/lottery.html"));
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
        assertTrue(WebSecurity.safeApiPath("/api/user/lottery"));
        assertTrue(WebSecurity.safeApiPath("/api/lottery"));
        assertTrue(WebSecurity.safeApiPath("/api/lottery/draw"));
        assertTrue(WebSecurity.safeApiPath("/api/lottery/cancel"));
        assertTrue(WebSecurity.safeApiPath("/api/world-stack/icon"));
        assertTrue(WebSecurity.safeApiPath("/api/world-stack/icon/delete"));
        assertTrue(WebSecurity.safeApiPath("/api/diff/chunk"));
        assertTrue(WebSecurity.safeApiPath("/api/diff/chunk/restore"));
        assertTrue(WebSecurity.safeApiPath("/api/diff/view"));
        assertTrue(WebSecurity.safeApiPath("/api/config/retention-estimate"));
        assertTrue(WebSecurity.safeApiPath("/api/config/pages"));
        assertTrue(WebSecurity.safeApiPath("/api/config/preview"));
        assertTrue(WebSecurity.safeApiPath("/api/announcements"));
        assertTrue(WebSecurity.safeApiPath("/api/announcements/preview"));
        assertTrue(WebSecurity.safeApiPath("/api/announcements/send"));
        assertTrue(WebSecurity.safeApiPath("/api/announcements/stop"));
        assertTrue(WebSecurity.safeApiPath("/api/agent/whoami"));
        assertTrue(WebSecurity.safeApiPath("/api/agent/tokens"));
        assertTrue(WebSecurity.safeApiPath("/api/identity/data"));
        assertTrue(WebSecurity.safeApiPath("/api/identity/data/backup"));
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
