package com.shenqianye.samsara.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /openapi.json 的公开路径必须仍在 WebSecurity 白名单内，避免契约与实现漂移。 */
class OpenApiContractTest {
    @Test
    void documentedPathsAreAllowed() {
        try (InputStream in = OpenApiContractTest.class.getResourceAsStream("/web/openapi.json")) {
            assertNotNull(in, "缺少 /web/openapi.json");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertTrue(root.get("openapi").getAsString().startsWith("3."));
            JsonObject paths = root.getAsJsonObject("paths");
            assertNotNull(paths);
            assertTrue(paths.size() > 0);
            for (Map.Entry<String, JsonElement> entry : paths.entrySet()) {
                String path = entry.getKey();
                assertTrue(path.startsWith("/api/"), "非 API 路径: " + path);
                assertTrue(WebSecurity.safeApiPath(path), "OpenAPI 路径未加入 safeApiPath: " + path);
            }
        } catch (Exception error) {
            throw new AssertionError("读取 openapi.json 失败", error);
        }
    }
}
