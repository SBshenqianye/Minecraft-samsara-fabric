package com.shenqianye.samsara.web.http;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTTP 边界工具：查询串、数字参数、缓存策略、错误摘要。 */
class WebHttpTest {

    @Test
    void parseQueryDecodesAndKeepsFirstDuplicate() {
        Map<String, String> query = WebHttp.parseQuery("a=1&b=%E5%9C%B0&a=2&flag&empty=");

        assertEquals("1", query.get("a"));
        assertEquals("地", query.get("b"));
        assertEquals("", query.get("flag"));
        assertEquals("", query.get("empty"));
    }

    @Test
    void parseQuerySkipsBrokenEscapesInsteadOfFailing() {
        Map<String, String> query = WebHttp.parseQuery("bad=%E0%A4%A&ok=1");

        assertEquals("1", query.get("ok"));
        assertNull(query.get("bad"));
    }

    @Test
    void parseQueryHandlesEmptyInput() {
        assertTrue(WebHttp.parseQuery(null).isEmpty());
        assertTrue(WebHttp.parseQuery("").isEmpty());
    }

    @Test
    void intParamFallsBackOnlyForMissingOrInvalidValues() {
        Map<String, String> query = WebHttp.parseQuery("offset=20&limit=abc&zero=0");

        assertEquals(20, WebHttp.intParam(query, "offset", 5));
        assertEquals(5, WebHttp.intParam(query, "limit", 5));
        assertEquals(0, WebHttp.intParam(query, "zero", 5));
        assertEquals(5, WebHttp.intParam(query, "missing", 5));
        assertNull(WebHttp.intParam(" "));
        assertNull(WebHttp.intParam("12x"));
    }

    @Test
    void cacheControlKeepsBusinessJsonUncached() {
        assertEquals("no-store", WebHttp.cacheControl("/api/lottery"));
        assertEquals("no-store", WebHttp.cacheControl(null));
        assertTrue(WebHttp.cacheControl("/vue/assets/app.js").contains("immutable"));
        assertEquals("public, max-age=3600", WebHttp.cacheControl("/api/user/map/tile"));
    }

    @Test
    void errorDetailPrefersTypeAndTrimsToOneLine() {
        assertEquals("IllegalStateException: boom",
                WebHttp.errorDetail(new IllegalStateException("boom")));
        assertEquals("NullPointerException",
                WebHttp.errorDetail(new NullPointerException()));
        String longMessage = "x".repeat(400);
        String detail = WebHttp.errorDetail(new RuntimeException(longMessage));
        assertTrue(detail.length() < 340, detail.length() + "");
        assertTrue(detail.endsWith("..."), detail);
    }
}
