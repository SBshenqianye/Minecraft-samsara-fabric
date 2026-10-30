package com.shenqianye.samsara.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 输出格式与权限树 HTTP 集成测试：鉴权、非法字段 400、保存 200、重载。 */
class OutputPermissionWebServerTest {
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

    private void start() {
        server = new LocalWebServer(new FakeConfig(), null, new FakeOutput(),
                new FakePermission(), TOKEN, 0);
        assertTrue(server.start());
    }

    @Test
    void outputApiRequiresBearerToken() throws Exception {
        start();
        assertEquals(401, request("GET", "/api/output", null, null).statusCode());
        assertEquals(401, request("GET", "/api/output?token=" + TOKEN, null, null).statusCode());
    }

    @Test
    void getOutputReturnsMessages() throws Exception {
        start();
        HttpResponse<String> response = request("GET", "/api/output", null, TOKEN);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("where.result"));
    }

    @Test
    void invalidTemplateIs400WithFields() throws Exception {
        start();
        HttpResponse<String> response = request("PUT", "/api/output",
                "{\"key\":\"bad\",\"parts\":[]}", TOKEN);
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("fields"));
        assertTrue(response.body().contains("parts"));
    }

    @Test
    void saveOutputSucceeds() throws Exception {
        start();
        HttpResponse<String> response = request("PUT", "/api/output",
                "{\"key\":\"where.result\",\"parts\":[{\"var\":\"x\"}]}", TOKEN);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"ok\":true"));
    }

    @Test
    void previewReturnsServerFragmentsAndRejectsBadRequest() throws Exception {
        start();
        HttpResponse<String> ok = request("POST", "/api/output/preview",
                "{\"key\":\"where.result\",\"parts\":[{\"var\":\"x\"}]}", TOKEN);
        assertEquals(200, ok.statusCode());
        assertTrue(ok.body().contains("fragments"));
        assertEquals(400, request("POST", "/api/output/preview",
                "{\"key\":\"bad\",\"parts\":[]}", TOKEN).statusCode());
    }

    @Test
    void outputReloadIsPostOnly() throws Exception {
        start();
        assertEquals(405, request("GET", "/api/output/reload", null, TOKEN).statusCode());
        assertEquals(200, request("POST", "/api/output/reload", null, TOKEN).statusCode());
    }

    @Test
    void permissionTreeApiWorksAndRejectsInvalidNode() throws Exception {
        start();
        HttpResponse<String> model = request("GET", "/api/permissions", null, TOKEN);
        assertEquals(200, model.statusCode());
        assertTrue(model.body().contains("/samsara"));

        assertEquals(200, request("PUT", "/api/permissions",
                "{\"nodes\":[{\"path\":\"claim info\",\"level\":\"op\"}]}", TOKEN).statusCode());
        assertEquals(400, request("PUT", "/api/permissions",
                "{\"nodes\":[{\"path\":\"bad\",\"level\":\"nope\"}]}", TOKEN).statusCode());
        assertEquals(405, request("GET", "/api/permissions/reload", null, TOKEN).statusCode());
        assertEquals(200, request("POST", "/api/permissions/reload", null, TOKEN).statusCode());
    }

    private static final class FakeConfig implements ConfigService {
        @Override
        public Map<String, Object> configModel() {
            return Map.of("files", List.of());
        }

        @Override
        public SaveOutcome save(String fileId, Map<String, Object> values) {
            return new SaveOutcome(true, Map.of());
        }

        @Override
        public Map<String, Object> reload() {
            return Map.of("ok", true, "config", configModel());
        }

        @Override
        public Map<String, Object> status() {
            return Map.of("version", "test");
        }
    }

    private static final class FakeOutput implements OutputWebService {
        @Override
        public Map<String, Object> model() {
            return Map.of("ok", true, "messages", List.of(
                    Map.of("key", "where.result", "group", "位置")));
        }

        @Override
        public Map<String, Object> save(Map<String, Object> body) {
            if ("bad".equals(body.get("key"))) {
                return Map.of("ok", false, "error", "校验失败",
                        "fields", Map.of("parts", "缺少片段"));
            }
            return Map.of("ok", true, "output", model());
        }

        @Override
        public Map<String, Object> preview(Map<String, Object> body) {
            if ("bad".equals(body.get("key"))) {
                return Map.of("ok", false, "error", "校验失败",
                        "fields", Map.of("parts", "缺少片段"));
            }
            return Map.of("ok", true, "key", body.get("key"),
                    "fragments", List.of(Map.of("text", "坐标 ", "kind", "text")));
        }

        @Override
        public Map<String, Object> reload() {
            return Map.of("ok", true, "output", model());
        }
    }

    private static final class FakePermission implements PermissionWebService {
        @Override
        public Map<String, Object> model() {
            return Map.of("ok", true, "tree", List.of(
                    Map.of("path", "*", "label", "/samsara", "children", List.of())));
        }

        @Override
        public Map<String, Object> save(Map<String, Object> body) {
            Object nodes = body.get("nodes");
            if (nodes instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> node
                    && "bad".equals(node.get("path"))) {
                return Map.of("ok", false, "error", "校验失败",
                        "fields", Map.of("nodes[0].level", "等级非法"));
            }
            return Map.of("ok", true, "permissions", model());
        }

        @Override
        public Map<String, Object> reload() {
            return Map.of("ok", true, "permissions", model());
        }
    }
}
