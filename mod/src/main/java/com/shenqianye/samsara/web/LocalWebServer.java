package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地配置面板（F-123~F-126）。
 *
 * <p>只绑定 {@code 127.0.0.1}；默认端口 8720。API 要求
 * {@code Authorization: Bearer <token>}，常量时间比较，不接受 URL 查询参数。
 *
 * <p>页面与 API 均不触碰世界数据：只读写 {@code config/samsara/*.yml}。
 * 静态资源固定路径，不做路径拼接，杜绝目录穿越。
 */
public final class LocalWebServer {
    /** 只允许回环地址（首版不提供公网监听开关）。 */
    public static final String BIND_ADDRESS = "127.0.0.1";
    private static final Logger LOG = LoggerFactory.getLogger("samsara-web");
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();

    private final ConfigService service;
    private final IdentityWebService identityService;
    private final OutputWebService outputService;
    private final PermissionWebService permissionService;
    private final String token;
    private final int port;
    private HttpServer server;

    public LocalWebServer(ConfigService service, String token, int port) {
        this(service, null, null, null, token, port);
    }

    public LocalWebServer(ConfigService service, IdentityWebService identityService,
                          String token, int port) {
        this(service, identityService, null, null, token, port);
    }

    public LocalWebServer(ConfigService service, IdentityWebService identityService,
                          OutputWebService outputService,
                          PermissionWebService permissionService,
                          String token, int port) {
        this.service = service;
        this.identityService = identityService;
        this.outputService = outputService;
        this.permissionService = permissionService;
        this.token = token == null ? "" : token;
        this.port = port;
    }

    /** 启动；端口占用返回 false（调用方只记 WARN，不阻塞服务器）。 */
    public synchronized boolean start() {
        if (server != null) {
            return true;
        }
        if (token.isBlank()) {
            LOG.warn("[web] 未配置 token，拒绝启动本地配置面板");
            return false;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(BIND_ADDRESS, port), 0);
        } catch (IOException e) {
            LOG.warn("[web] 端口 {} 无法监听（{}），本地配置面板已跳过，服务器继续运行",
                    port, e.getMessage());
            server = null;
            return false;
        }
        server.createContext("/", this::handleStatic);
        server.createContext("/api/config", this::handleConfig);
        server.createContext("/api/reload", this::handleReload);
        server.createContext("/api/status", this::handleStatus);
        if (identityService != null) {
            server.createContext("/api/identity", this::handleIdentity);
        }
        if (outputService != null) {
            server.createContext("/api/output", this::handleOutput);
        }
        if (permissionService != null) {
            server.createContext("/api/permissions", this::handlePermissions);
        }
        server.setExecutor(null);
        server.start();
        return true;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            LOG.info("[web] 本地配置面板已停止");
        }
    }

    /** 实际监听端口（测试用；端口占用/未启动时返回配置端口）。 */
    public int port() {
        HttpServer current = server;
        return current == null ? port : current.getAddress().getPort();
    }

    // ---- handlers ----

    private void handleStatic(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                status = 405;
                sendText(exchange, status, "text/plain; charset=utf-8", "Method Not Allowed");
                return;
            }
            String resource = WebSecurity.staticResource(exchange.getRequestURI().getPath());
            if (resource == null) {
                status = 404;
                sendText(exchange, status, "text/plain; charset=utf-8", "Not Found");
                return;
            }
            byte[] body;
            try (InputStream in = LocalWebServer.class.getResourceAsStream(resource)) {
                if (in == null) {
                    status = 404;
                    sendText(exchange, status, "text/plain; charset=utf-8", "Not Found");
                    return;
                }
                body = in.readAllBytes();
            }
            String type = resource.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : resource.endsWith(".css") ? "text/css; charset=utf-8"
                    : "text/html; charset=utf-8";
            status = 200;
            sendBytes(exchange, status, type, body);
        } finally {
            log(exchange, status, start);
        }
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (!authorized(exchange)) {
                status = 401;
                json(exchange, status, Map.of("error", "未授权：缺少或错误的 Bearer token"));
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                json(exchange, 200, service.configModel());
                return;
            }
            if (!"PUT".equals(method)) {
                status = 405;
                json(exchange, status, Map.of("error", "Method Not Allowed"));
                return;
            }
            String body;
            try {
                body = readBody(exchange);
            } catch (BodyTooLarge e) {
                status = 413;
                json(exchange, status, Map.of("error", "请求体过大"));
                return;
            }
            SaveRequest request = parseSave(body);
            if (request == null) {
                status = 400;
                json(exchange, status, Map.of("error",
                        "请求体应为 {\"file\":\"commands\",\"values\":{...}}"));
                return;
            }
            ConfigService.SaveOutcome outcome = service.save(request.file(), request.values());
            if (!outcome.ok()) {
                status = 400;
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "配置校验失败");
                error.put("fields", outcome.errors());
                json(exchange, status, error);
                return;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("config", service.configModel());
            json(exchange, 200, result);
        } catch (RuntimeException e) {
            status = 500;
            json(exchange, status, Map.of("error", "服务器内部错误"));
            LOG.warn("[web] 处理 {} 失败: {}", exchange.getRequestURI().getPath(), e.getMessage());
        } finally {
            log(exchange, status, start);
        }
    }

    private void handleReload(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (!authorized(exchange)) {
                status = 401;
                json(exchange, status, Map.of("error", "未授权：缺少或错误的 Bearer token"));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                status = 405;
                json(exchange, status, Map.of("error", "Method Not Allowed"));
                return;
            }
            json(exchange, 200, service.reload());
        } finally {
            log(exchange, status, start);
        }
    }

    private void handleIdentity(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (!authorized(exchange)) {
                status = 401;
                json(exchange, status, Map.of("error", "未授权：缺少或错误的 Bearer token"));
                return;
            }
            if (identityService == null) {
                status = 503;
                json(exchange, status, Map.of("error", "身份服务未启动"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Map<String, Object> body = Map.of();
            if ("POST".equals(method) || "PUT".equals(method)) {
                try {
                    body = parseObject(readBody(exchange));
                } catch (BodyTooLarge e) {
                    status = 413;
                    json(exchange, status, Map.of("error", "请求体过大"));
                    return;
                } catch (RuntimeException e) {
                    status = 400;
                    json(exchange, status, Map.of("error", "请求体应为 JSON 对象"));
                    return;
                }
            }
            Map<String, Object> result;
            switch (path) {
                case "/api/identity" -> {
                    if ("GET".equals(method)) {
                        result = identityService.model();
                    } else if ("PUT".equals(method) || "POST".equals(method)) {
                        result = identityService.save(body);
                    } else {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    }
                }
                case "/api/identity/preview" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    } else {
                        result = identityService.preview(body);
                    }
                }
                case "/api/identity/rebind" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    } else {
                        result = identityService.rebind(body);
                    }
                }
                case "/api/identity/reload" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    } else {
                        result = identityService.reload();
                    }
                }
                case "/api/identity/audit" -> {
                    if (!"GET".equals(method)) {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    } else {
                        result = identityService.audit();
                    }
                }
                case "/api/identity/generate" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    } else {
                        result = identityService.generate();
                    }
                }
                default -> {
                    status = 404;
                    result = Map.of("error", "Not Found");
                }
            }
            if (status == 200 && Boolean.FALSE.equals(result.get("ok"))) {
                status = 400;
            }
            json(exchange, status, result);
        } catch (RuntimeException e) {
            status = 500;
            json(exchange, status, Map.of("error", "服务器内部错误"));
            LOG.warn("[web] 处理 {} 失败: {}", exchange.getRequestURI().getPath(), e.getMessage());
        } finally {
            log(exchange, status, start);
        }
    }

    private void handleOutput(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (!authorized(exchange)) {
                status = 401;
                json(exchange, status, Map.of("error", "未授权：缺少或错误的 Bearer token"));
                return;
            }
            if (outputService == null) {
                status = 503;
                json(exchange, status, Map.of("error", "输出格式服务未启动"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Map<String, Object> body = Map.of();
            if ("POST".equals(method) || "PUT".equals(method)) {
                try {
                    body = parseObject(readBody(exchange));
                } catch (BodyTooLarge e) {
                    status = 413;
                    json(exchange, status, Map.of("error", "请求体过大"));
                    return;
                } catch (RuntimeException e) {
                    status = 400;
                    json(exchange, status, Map.of("error", "请求体应为 JSON 对象"));
                    return;
                }
            }
            Map<String, Object> result;
            switch (path) {
                case "/api/output" -> {
                    if ("GET".equals(method)) {
                        result = outputService.model();
                    } else if ("PUT".equals(method)) {
                        result = outputService.save(body);
                    } else {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    }
                }
                case "/api/output/preview" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    } else {
                        result = outputService.preview(body);
                    }
                }
                case "/api/output/reload" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    } else {
                        result = outputService.reload();
                    }
                }
                default -> {
                    status = 404;
                    result = Map.of("error", "Not Found");
                }
            }
            if (status == 200 && Boolean.FALSE.equals(result.get("ok"))) {
                status = 400;
            }
            json(exchange, status, result);
        } catch (RuntimeException e) {
            status = 500;
            json(exchange, status, Map.of("error", "服务器内部错误"));
            LOG.warn("[web] 处理 {} 失败: {}", exchange.getRequestURI().getPath(), e.getMessage());
        } finally {
            log(exchange, status, start);
        }
    }

    private void handlePermissions(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (!authorized(exchange)) {
                status = 401;
                json(exchange, status, Map.of("error", "未授权：缺少或错误的 Bearer token"));
                return;
            }
            if (permissionService == null) {
                status = 503;
                json(exchange, status, Map.of("error", "权限树服务未启动"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Map<String, Object> body = Map.of();
            if ("PUT".equals(method) || "POST".equals(method)) {
                try {
                    body = parseObject(readBody(exchange));
                } catch (BodyTooLarge e) {
                    status = 413;
                    json(exchange, status, Map.of("error", "请求体过大"));
                    return;
                } catch (RuntimeException e) {
                    status = 400;
                    json(exchange, status, Map.of("error", "请求体应为 JSON 对象"));
                    return;
                }
            }
            Map<String, Object> result;
            switch (path) {
                case "/api/permissions" -> {
                    if ("GET".equals(method)) {
                        result = permissionService.model();
                    } else if ("PUT".equals(method)) {
                        result = permissionService.save(body);
                    } else {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    }
                }
                case "/api/permissions/reload" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    } else {
                        result = permissionService.reload();
                    }
                }
                default -> {
                    status = 404;
                    result = Map.of("error", "Not Found");
                }
            }
            if (status == 200 && Boolean.FALSE.equals(result.get("ok"))) {
                status = 400;
            }
            json(exchange, status, result);
        } catch (RuntimeException e) {
            status = 500;
            json(exchange, status, Map.of("error", "服务器内部错误"));
            LOG.warn("[web] 处理 {} 失败: {}", exchange.getRequestURI().getPath(), e.getMessage());
        } finally {
            log(exchange, status, start);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String body) {
        Map<String, Object> map = GSON.fromJson(body, MAP_TYPE);
        return map == null ? Map.of() : map;
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (!authorized(exchange)) {
                status = 401;
                json(exchange, status, Map.of("error", "未授权：缺少或错误的 Bearer token"));
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                status = 405;
                json(exchange, status, Map.of("error", "Method Not Allowed"));
                return;
            }
            json(exchange, 200, service.status());
        } finally {
            log(exchange, status, start);
        }
    }

    // ---- 工具 ----

    private boolean authorized(HttpExchange exchange) {
        return WebSecurity.authorized(token, exchange.getRequestHeaders()
                .getFirst("Authorization"));
    }

    private static String readBody(HttpExchange exchange) throws IOException, BodyTooLarge {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            long length;
            try {
                length = Long.parseLong(declared);
            } catch (NumberFormatException e) {
                throw new BodyTooLarge();
            }
            if (!WebSecurity.bodyWithinLimit(length)) {
                throw new BodyTooLarge();
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(WebSecurity.MAX_BODY_BYTES + 1);
            if (bytes.length > WebSecurity.MAX_BODY_BYTES) {
                throw new BodyTooLarge();
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static SaveRequest parseSave(String body) {
        try {
            JsonElement parsed = GSON.fromJson(body, JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();
            if (!object.has("file") || !object.get("file").isJsonPrimitive()) {
                return null;
            }
            String file = object.get("file").getAsString();
            if (!object.has("values") || !object.get("values").isJsonObject()) {
                return null;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : object.getAsJsonObject("values").entrySet()) {
                values.put(e.getKey(), GSON.fromJson(e.getValue(), Object.class));
            }
            return new SaveRequest(file, values);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        sendBytes(exchange, status, "application/json; charset=utf-8",
                GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void sendText(HttpExchange exchange, int status, String type, String body)
            throws IOException {
        sendBytes(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange exchange, int status, String type, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void log(HttpExchange exchange, int status, long startMs) {
        URI uri = exchange.getRequestURI();
        // 只记录方法/路径/状态码/耗时；不记录 token，也不记录查询串（避免泄漏）。
        LOG.debug("[web] {} {} → {} ({}ms)", exchange.getRequestMethod(), uri.getPath(),
                status, System.currentTimeMillis() - startMs);
    }

    private record SaveRequest(String file, Map<String, Object> values) {
    }

    private static final class BodyTooLarge extends Exception {
    }
}
