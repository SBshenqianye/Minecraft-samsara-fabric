package com.shenqianye.samsara.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本地 Web 集成测试：鉴权失败、非法值拒绝、保存成功、重载、端口占用降级。 */
class LocalWebServerTest {
    private static final String TOKEN = "test-token";
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private LocalWebServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private String base() {
        return "http://127.0.0.1:" + server.port();
    }

    private HttpResponse<String> request(String method, String path, String body,
                                         String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private FakeService start() {
        FakeService service = new FakeService();
        server = new LocalWebServer(service, TOKEN, 0);
        assertTrue(server.start());
        return service;
    }

    @Test
    void apiRejectsMissingOrWrongToken() throws Exception {
        start();
        assertEquals(401, request("GET", "/api/config", null, null).statusCode());
        assertEquals(401, request("GET", "/api/config", null, "wrong").statusCode());
        assertEquals(401, request("GET", "/api/status?token=" + TOKEN, null, null).statusCode(),
                "URL 查询参数不能当 token");
    }

    @Test
    void staticPageIsServedWithoutToken() throws Exception {
        start();
        HttpResponse<String> response = request("GET", "/", null, null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("samsara 配置"));
    }

    @Test
    void invalidValueIsRejectedWithFieldError() throws Exception {
        start();
        HttpResponse<String> response = request("PUT", "/api/config",
                "{\"file\":\"commands\",\"values\":{\"completion.maxItems\":1}}", TOKEN);

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("completion.maxItems"));
        assertTrue(response.body().contains("fields"));
    }

    @Test
    void saveSucceedsAndReturnsUpdatedModel() throws Exception {
        FakeService service = start();
        HttpResponse<String> response = request("PUT", "/api/config",
                "{\"file\":\"commands\",\"values\":{\"where.enabled\":false}}", TOKEN);

        assertEquals(200, response.statusCode());
        assertEquals(false, service.saved.get("where.enabled"));
        assertTrue(response.body().contains("\"ok\":true"));
    }

    @Test
    void reloadIsPostOnly() throws Exception {
        FakeService service = start();
        assertEquals(405, request("GET", "/api/reload", null, TOKEN).statusCode());
        assertEquals(200, request("POST", "/api/reload", null, TOKEN).statusCode());
        assertEquals(1, service.reloads);
    }

    @Test
    void occupiedPortDegradesWithoutThrowing() {
        start();
        LocalWebServer second = new LocalWebServer(new FakeService(), TOKEN, server.port());
        assertFalse(second.start(), "端口占用应返回 false，由调用方记 WARN");
        second.stop();
    }

    @Test
    void oversizedBodyIsRejected() throws Exception {
        start();
        String huge = "{\"file\":\"commands\",\"values\":{\"x\":\"" + "a".repeat(
                WebSecurity.MAX_BODY_BYTES) + "\"}}";
        assertEquals(413, request("PUT", "/api/config", huge, TOKEN).statusCode());
    }

    /** 假配置服务：只验证 HTTP 层，不依赖 Fabric/ConfigManager。 */
    private static final class FakeService implements ConfigService {
        final Map<String, Object> saved = new LinkedHashMap<>();
        int reloads;

        @Override
        public Map<String, Object> configModel() {
            return Map.of("files", List.of(Map.of(
                    "id", "commands",
                    "name", "commands.yml",
                    "path", "/tmp/commands.yml",
                    "entries", List.of(Map.of(
                            "path", "where.enabled",
                            "type", "boolean",
                            "value", true,
                            "default", true)))));
        }

        @Override
        public SaveOutcome save(String fileId, Map<String, Object> values) {
            if (values.containsKey("completion.maxItems")) {
                return new SaveOutcome(false,
                        Map.of("completion.maxItems", "不能小于 4"));
            }
            saved.putAll(values);
            return new SaveOutcome(true, Map.of());
        }

        @Override
        public Map<String, Object> reload() {
            reloads++;
            return Map.of("ok", true, "config", configModel());
        }

        @Override
        public Map<String, Object> status() {
            return Map.of("version", "0.18.0-test", "worldDataRoot", "/tmp/world/samsara");
        }
    }
}
