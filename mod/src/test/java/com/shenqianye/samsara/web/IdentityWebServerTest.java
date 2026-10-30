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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 身份组独立 API：鉴权、字段级错误、预览、重绑二次确认。 */
class IdentityWebServerTest {
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

    private FakeIdentity start() {
        FakeIdentity identity = new FakeIdentity();
        server = new LocalWebServer(new FakeConfig(), identity, TOKEN, 0);
        assertTrue(server.start());
        return identity;
    }

    private HttpResponse<String> request(String method, String path, String body, String token)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void identityApiRequiresToken() throws Exception {
        start();
        assertEquals(401, request("GET", "/api/identity", null, null).statusCode());
        assertEquals(401, request("PUT", "/api/identity", "{}", "wrong").statusCode());
    }

    @Test
    void modelIsServedAndSaveValidationReturnsFieldErrors() throws Exception {
        start();
        assertEquals(200, request("GET", "/api/identity", null, TOKEN).statusCode());
        HttpResponse<String> response = request("PUT", "/api/identity",
                "{\"groups\":[{\"name\":\"A\",\"data\":\"bad\"}]}", TOKEN);
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("groups[0].data"));
    }

    @Test
    void previewReturnsCountsAndRebindRequiresConfirm() throws Exception {
        start();
        HttpResponse<String> preview = request("POST", "/api/identity/preview",
                "{\"data\":\"4f1c2b8e-0000-0000-0000-000000000000\"}", TOKEN);
        assertEquals(200, preview.statusCode());
        assertTrue(preview.body().contains("claims"));

        HttpResponse<String> rebind = request("POST", "/api/identity/rebind",
                "{\"group\":\"A\",\"newData\":\"4f1c2b8e-0000-0000-0000-000000000001\"}",
                TOKEN);
        assertEquals(400, rebind.statusCode());
        assertTrue(rebind.body().contains("二次确认"));
    }

    private static final class FakeIdentity implements IdentityWebService {
        @Override
        public Map<String, Object> model() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("config", Map.of("enabled", false, "groups", java.util.List.of()));
            return out;
        }

        @Override
        public Map<String, Object> save(Map<String, Object> body) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("errors", Map.of("groups[0].data", "UUID 格式不正确"));
            return out;
        }

        @Override
        public Map<String, Object> preview(Map<String, Object> body) {
            return Map.of("ok", true, "claims", 0, "playerdata", false, "tracks", 0,
                    "activity", 0, "online", false);
        }

        @Override
        public Map<String, Object> rebind(Map<String, Object> body) {
            return Map.of("ok", false, "error", "重绑必须二次确认（confirm=true）");
        }

        @Override
        public Map<String, Object> reload() {
            return Map.of("ok", true);
        }

        @Override
        public Map<String, Object> audit() {
            return Map.of("ok", true, "text", "ok");
        }

        @Override
        public Map<String, Object> generate() {
            return Map.of("ok", true, "data", "4f1c2b8e-0000-0000-0000-000000000000");
        }
    }

    private static final class FakeConfig implements ConfigService {
        @Override
        public Map<String, Object> configModel() {
            return Map.of("files", java.util.List.of());
        }

        @Override
        public SaveOutcome save(String fileId, Map<String, Object> values) {
            return new SaveOutcome(true, Map.of());
        }

        @Override
        public Map<String, Object> reload() {
            return Map.of("ok", true);
        }

        @Override
        public Map<String, Object> status() {
            return Map.of("version", "0.20.0-test");
        }
    }
}
