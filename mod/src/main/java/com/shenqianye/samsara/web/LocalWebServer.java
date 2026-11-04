package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.Headers;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final WebMapService mapService;
    private final WorldStackWebService worldStackService;
    private final ServerWebService serverService;
    private final ChunkDiffWebService diffService;
    private final WebUserService userService;
    private final LotteryWebService lotteryService;
    private final String token;
    private final int port;
    private HttpServer server;
    private ExecutorService executor;

    public LocalWebServer(ConfigService service, String token, int port) {
        this(service, null, null, null, null, token, port);
    }

    public LocalWebServer(ConfigService service, IdentityWebService identityService,
                          String token, int port) {
        this(service, identityService, null, null, null, token, port);
    }

    public LocalWebServer(ConfigService service, IdentityWebService identityService,
                          OutputWebService outputService,
                          PermissionWebService permissionService,
                          String token, int port) {
        this(service, identityService, outputService, permissionService, null, token, port);
    }

    public LocalWebServer(ConfigService service, IdentityWebService identityService,
                          OutputWebService outputService,
                          PermissionWebService permissionService,
                          WebMapService mapService,
                          String token, int port) {
        this(service, identityService, outputService, permissionService, mapService, null,
                token, port);
    }

    public LocalWebServer(ConfigService service, IdentityWebService identityService,
                          OutputWebService outputService,
                          PermissionWebService permissionService,
                          WebMapService mapService,
                          WorldStackWebService worldStackService,
                          String token, int port) {
        this(service, identityService, outputService, permissionService, mapService,
                worldStackService, null, token, port);
    }

    public LocalWebServer(ConfigService service, IdentityWebService identityService,
                          OutputWebService outputService,
                          PermissionWebService permissionService,
                          WebMapService mapService,
                          WorldStackWebService worldStackService,
                          ServerWebService serverService,
                          String token, int port) {
        this(service, identityService, outputService, permissionService, mapService,
                worldStackService, serverService, null, token, port);
    }

    public LocalWebServer(ConfigService service, IdentityWebService identityService,
                          OutputWebService outputService,
                          PermissionWebService permissionService,
                          WebMapService mapService,
                          WorldStackWebService worldStackService,
                          ServerWebService serverService,
                          ChunkDiffWebService diffService,
                          String token, int port) {
        this(service, identityService, outputService, permissionService, mapService,
                worldStackService, serverService, diffService, null, token, port);
    }

    public LocalWebServer(ConfigService service, IdentityWebService identityService,
                          OutputWebService outputService,
                          PermissionWebService permissionService,
                          WebMapService mapService,
                          WorldStackWebService worldStackService,
                          ServerWebService serverService,
                          ChunkDiffWebService diffService,
                          WebUserService userService,
                          String token, int port) {
        this(service, identityService, outputService, permissionService, mapService,
                worldStackService, serverService, diffService, userService, null, token, port);
    }

    public LocalWebServer(ConfigService service, IdentityWebService identityService,
                          OutputWebService outputService,
                          PermissionWebService permissionService,
                          WebMapService mapService,
                          WorldStackWebService worldStackService,
                          ServerWebService serverService,
                          ChunkDiffWebService diffService,
                          WebUserService userService,
                          LotteryWebService lotteryService,
                          String token, int port) {
        this.service = service;
        this.identityService = identityService;
        this.outputService = outputService;
        this.permissionService = permissionService;
        this.mapService = mapService;
        this.worldStackService = worldStackService;
        this.serverService = serverService;
        this.diffService = diffService;
        this.userService = userService;
        this.lotteryService = lotteryService;
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
        if (mapService != null) {
            server.createContext("/api/map", this::handleMap);
        }
        if (userService != null) {
            server.createContext("/api/user", this::handleUser);
        }
        if (diffService != null) {
            server.createContext("/api/diff", this::handleDiff);
        }
        if (worldStackService != null) {
            server.createContext("/api/world-stack", this::handleWorldStack);
        }
        if (serverService != null) {
            server.createContext("/api/server", this::handleServer);
        }
        if (lotteryService != null) {
            server.createContext("/api/lottery", this::handleLottery);
        }
        server.createContext("/api/audit", this::handleAudit);
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "samsara-web");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();
        return true;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            LOG.info("[web] 本地配置面板已停止");
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
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
                    : resource.endsWith(".png") ? "image/png"
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
            if ("GET".equals(method)
                    && "/api/config/retention-estimate".equals(exchange.getRequestURI().getPath())) {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                String curveJson = query.get("curve");
                List<Map<String, Object>> raw;
                try {
                    raw = curveJson == null || curveJson.isBlank() ? List.of()
                            : GSON.fromJson(curveJson,
                            new TypeToken<List<Map<String, Object>>>() { }.getType());
                } catch (RuntimeException e) {
                    status = 400;
                    json(exchange, status, Map.of("error", "curve 应为 JSON 数组"));
                    return;
                }
                boolean keepAll = "1".equals(query.get("keepAll"))
                        || "true".equalsIgnoreCase(query.get("keepAll"));
                var usage = com.shenqianye.samsara.track.TrackRecorder.getInstance().usage();
                long actualPerSample = usage.totalSamples() > 0
                        ? Math.max(1L, Math.round(usage.bytesPerSample()))
                        : com.shenqianye.samsara.track.TrackRetention.BYTES_PER_SAMPLE;
                var estimate = com.shenqianye.samsara.track.TrackRetention.estimate(
                        com.shenqianye.samsara.track.TrackRetention.parseCurve(raw),
                        keepAll, actualPerSample);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("bytesPerSample", estimate.bytesPerSample());
                out.put("actualBytesPerSample", usage.bytesPerSample());
                out.put("actualTotalBytes", usage.totalBytes());
                out.put("actualTotalSamples", usage.totalSamples());
                out.put("actualFileCount", usage.fileCount());
                out.put("actualPlayerCount", usage.playerCount());
                out.put("actualBytesPerPlayer", usage.bytesPerPlayer());
                out.put("freshSamplesPerDay", estimate.freshSamplesPerDay());
                out.put("freshBytesPerDay", estimate.freshBytesPerDay());
                out.put("steadyBytes", estimate.steadyBytes());
                out.put("growthPerDay", estimate.growthPerDay());
                out.put("unbounded", estimate.unbounded());
                out.put("keepAll", estimate.keepAll());
                out.put("warning", estimate.warning());
                List<Map<String, Object>> tiers = new java.util.ArrayList<>();
                for (var tier : estimate.tiers()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("ageDays", tier.ageDays());
                    item.put("keepRatio", tier.keepRatio());
                    item.put("bytesPerDay", tier.bytesPerDay());
                    item.put("cumulativeBytes", tier.cumulativeBytes());
                    item.put("older", tier.older());
                    tiers.add(item);
                }
                out.put("tiers", tiers);
                json(exchange, 200, out);
                return;
            }
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
                case "/api/identity/resolve" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    } else {
                        result = identityService.resolve(body);
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

    /**
     * 用户端只读路由：不需要 token，也不接受任何写方法。
     * 只暴露公开状态、世界栈列表、地图与在线玩家；管理端路径不在这里注册。
     */
    private void handleUser(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (userService == null) {
                status = 503;
                json(exchange, status, Map.of("error", "用户端 Web 未启动"));
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                status = 405;
                json(exchange, status, Map.of("error", "Method Not Allowed"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            if ("/api/user/status".equals(path)) {
                json(exchange, 200, userService.status());
                return;
            }
            if ("/api/user/worlds".equals(path)) {
                json(exchange, 200, userService.worlds());
                return;
            }
            if ("/api/user/lottery".equals(path)) {
                json(exchange, 200, userService.lottery());
                return;
            }
            if ("/api/user/lottery/values".equals(path)) {
                json(exchange, 200, userService.lotteryValues());
                return;
            }
            if ("/api/user/worlds/icon".equals(path)) {
                WorldStackIconService.IconFile icon = userService.icon(query.getOrDefault("stack", ""));
                if (icon == null) {
                    status = 404;
                    json(exchange, status, Map.of("error", "没有这个栈层图标"));
                    return;
                }
                sendBytes(exchange, 200, icon.mime(), icon.bytes());
                return;
            }
            if ("/api/user/map/avatar".equals(path)) {
                MojangSkinService.Skin skin = userService.avatar(
                        query.getOrDefault("uuid", ""), query.getOrDefault("name", ""));
                if (skin == null) {
                    status = 404;
                    json(exchange, status, Map.of("error", "没有可用的 Mojang 皮肤"));
                    return;
                }
                sendBytes(exchange, 200, skin.mime(), skin.bytes());
                return;
            }
            if ("/api/user/map".equals(path)) {
                json(exchange, 200, userService.mapModel());
                return;
            }
            if ("/api/user/map/players".equals(path)) {
                json(exchange, 200, userService.players());
                return;
            }
            if ("/api/user/map/live".equals(path)) {
                json(exchange, 200, userService.live());
                return;
            }
            if ("/api/user/map/tile".equals(path)) {
                String stack = query.getOrDefault("stack", "");
                String dimension = query.getOrDefault("dim", "");
                Integer rx = intParam(query.get("rx"));
                Integer rz = intParam(query.get("rz"));
                Integer scale = intParam(query.get("scale"));
                if (dimension.isBlank() || rx == null || rz == null || scale == null) {
                    status = 400;
                    json(exchange, status, Map.of("error",
                            "参数应为 stack=<栈层>&dim=<维度>&rx=<int>&rz=<int>&scale=1|2|4|8"));
                    return;
                }
                if (scale != 1 && scale != 2 && scale != 4 && scale != 8) {
                    status = 400;
                    json(exchange, status, Map.of("error", "scale 只能是 1、2、4、8"));
                    return;
                }
                byte[] png = userService.tile(stack, dimension, rx, rz, scale,
                        !"0".equals(query.get("shade")));
                if (png == null) {
                    status = 404;
                    json(exchange, status, Map.of("error", "没有这个地图区块"));
                    return;
                }
                sendBytes(exchange, 200, "image/png", png);
                return;
            }
            status = 404;
            json(exchange, status, Map.of("error", "Not Found"));
        } catch (RuntimeException e) {
            status = 500;
            json(exchange, status, Map.of("error", "服务器内部错误"));
            LOG.warn("[web] 用户端处理 {} 失败: {}", exchange.getRequestURI().getPath(),
                    e.getMessage());
        } finally {
            log(exchange, status, start);
        }
    }

    private void handleMap(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (!authorized(exchange)) {
                status = 401;
                json(exchange, status, Map.of("error", "未授权：缺少或错误的 Bearer token"));
                return;
            }
            if (mapService == null) {
                status = 503;
                json(exchange, status, Map.of("error", "地图服务未启动"));
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                status = 405;
                json(exchange, status, Map.of("error", "Method Not Allowed"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            if ("/api/map".equals(path)) {
                json(exchange, 200, mapService.model());
                return;
            }
            if ("/api/map/players".equals(path)) {
                json(exchange, 200, mapService.players());
                return;
            }
            if ("/api/map/live".equals(path)) {
                json(exchange, 200, mapService.live());
                return;
            }
            if ("/api/map/tile".equals(path)) {
                String stack = query.getOrDefault("stack", "");
                String dimension = query.getOrDefault("dim", "");
                Integer rx = intParam(query.get("rx"));
                Integer rz = intParam(query.get("rz"));
                Integer scale = intParam(query.get("scale"));
                if (dimension.isBlank() || rx == null || rz == null || scale == null) {
                    status = 400;
                    json(exchange, status, Map.of("error",
                            "参数应为 stack=<栈层>&dim=<维度>&rx=<int>&rz=<int>&scale=1|2|4|8"));
                    return;
                }
                if (scale != 1 && scale != 2 && scale != 4 && scale != 8) {
                    status = 400;
                    json(exchange, status, Map.of("error", "scale 只能是 1、2、4、8"));
                    return;
                }
                byte[] png = mapService.tile(stack, dimension, rx, rz, scale,
                        !"0".equals(query.get("shade")));
                if (png == null) {
                    status = 404;
                    json(exchange, status, Map.of("error", "没有这个地图区块"));
                    return;
                }
                sendBytes(exchange, 200, "image/png", png);
                return;
            }
            status = 404;
            json(exchange, status, Map.of("error", "Not Found"));
        } catch (RuntimeException e) {
            status = 500;
            json(exchange, status, Map.of("error", "服务器内部错误"));
            LOG.warn("[web] 处理 {} 失败: {}", exchange.getRequestURI().getPath(), e.getMessage());
        } finally {
            log(exchange, status, start);
        }
    }

    private void handleDiff(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (!authorized(exchange)) {
                status = 401;
                json(exchange, status, Map.of("error", "未授权：缺少或错误的 Bearer token"));
                return;
            }
            if (diffService == null) {
                status = 503;
                json(exchange, status, Map.of("error", "Diff 服务未启动"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            if ("GET".equals(method)) {
                if ("/api/diff/chunk".equals(path)) {
                    Integer cx = intParam(query.get("cx"));
                    Integer cz = intParam(query.get("cz"));
                    boolean load = "1".equals(query.get("load"));
                    json(exchange, 200, diffService.summary(
                            query.getOrDefault("stack", ""), query.getOrDefault("dim", ""),
                            cx, cz, query.get("to"), load));
                    return;
                }
                if ("/api/diff/view".equals(path) || "/api/diff/view/status".equals(path)) {
                    json(exchange, 200, diffService.viewStatus());
                    return;
                }
                status = 404;
                json(exchange, status, Map.of("error", "Not Found"));
                return;
            }
            if (!"POST".equals(method)) {
                status = 405;
                json(exchange, status, Map.of("error", "Method Not Allowed"));
                return;
            }
            Map<String, Object> body;
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
            if ("/api/diff/chunk/restore".equals(path)) {
                json(exchange, 200, diffService.restore(body));
                return;
            }
            if ("/api/diff/view".equals(path)) {
                json(exchange, 200, diffService.view(body));
                return;
            }
            status = 404;
            json(exchange, status, Map.of("error", "Not Found"));
        } catch (RuntimeException e) {
            status = 500;
            json(exchange, status, Map.of("error", "服务器内部错误"));
            LOG.warn("[web] 处理 {} 失败: {}", exchange.getRequestURI().getPath(), e.getMessage());
        } finally {
            log(exchange, status, start);
        }
    }

    private void handleServer(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (!authorized(exchange)) {
                status = 401;
                json(exchange, status, Map.of("error", "未授权：缺少或错误的 Bearer token"));
                return;
            }
            if (serverService == null) {
                status = 503;
                json(exchange, status, Map.of("error", "本体服务未启动"));
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
            if ("GET".equals(method)) {
                if ("/api/server".equals(path) || "/api/server/config".equals(path)
                        || "/api/server/backups".equals(path)) {
                    result = serverService.model();
                } else if ("/api/server/jobs".equals(path)) {
                    result = serverService.jobs();
                } else if ("/api/server/players".equals(path)) {
                    result = serverService.players();
                } else if ("/api/server/backups/download".equals(path)) {
                    String id = parseQuery(exchange.getRequestURI().getRawQuery()).get("id");
                    var file = serverService.backupFile(id);
                    if (file == null) {
                        status = 404;
                        json(exchange, status, Map.of("error", "备份不存在"));
                        return;
                    }
                    sendFile(exchange, 200, "application/zip", id + ".zip", file);
                    return;
                } else {
                    status = 404;
                    result = Map.of("error", "Not Found");
                }
            } else if ("POST".equals(method)) {
                switch (path) {
                    case "/api/server/config" -> result = serverService.saveConfig(body);
                    case "/api/server/backups/preview" -> result = serverService.previewCreate();
                    case "/api/server/backups" -> result = serverService.createBackup(body);
                    case "/api/server/backups/delete" -> result = serverService.deleteBackup(body);
                    case "/api/server/backups/preview-restore" ->
                            result = serverService.previewRestore(body);
                    case "/api/server/backups/restore" -> result = serverService.restore(body);
                    case "/api/server/console" -> result = serverService.console(body);
                    default -> {
                        status = 404;
                        result = Map.of("error", "Not Found");
                    }
                }
            } else {
                status = 405;
                result = Map.of("error", "Method Not Allowed");
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

    private void handleAudit(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (!authorized(exchange)) {
                status = 401;
                json(exchange, status, Map.of("error", "未授权：缺少或错误的 Bearer token"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                json(exchange, 200, Map.of("ok", true,
                        "entries", WebAuditService.get().list(100)));
                return;
            }
            if (!"POST".equals(method)) {
                status = 405;
                json(exchange, status, Map.of("error", "Method Not Allowed"));
                return;
            }
            Map<String, Object> body;
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
            String id = body.get("id") == null ? "" : String.valueOf(body.get("id"));
            Map<String, Object> result;
            if ("/api/audit/preview".equals(path)) {
                result = WebAuditService.get().preview(id);
            } else if ("/api/audit/rollback".equals(path)) {
                result = WebAuditService.get().rollback(id,
                        Boolean.TRUE.equals(body.get("confirm"))
                                || "true".equalsIgnoreCase(String.valueOf(body.get("confirm"))));
            } else {
                status = 404;
                result = Map.of("error", "Not Found");
            }
            if (status == 200 && Boolean.FALSE.equals(result.get("ok"))) status = 400;
            json(exchange, status, result);
        } catch (RuntimeException e) {
            status = 500;
            json(exchange, status, Map.of("error", "服务器内部错误：" + errorDetail(e)));
            LOG.warn("[web] 审计处理失败: {}", e.toString());
        } finally {
            log(exchange, status, start);
        }
    }

    private void handleWorldStack(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (!authorized(exchange)) {
                status = 401;
                json(exchange, status, Map.of("error", "未授权：缺少或错误的 Bearer token"));
                return;
            }
            if (worldStackService == null) {
                status = 503;
                json(exchange, status, Map.of("error", "世界栈服务未启动"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            int bodyLimit = "/api/world-stack/icon".equals(path)
                    ? WebSecurity.MAX_ICON_BODY_BYTES : WebSecurity.MAX_BODY_BYTES;
            Map<String, Object> body = Map.of();
            if ("POST".equals(method) || "PUT".equals(method)) {
                try {
                    body = parseObject(readBody(exchange, bodyLimit));
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
            Object result;
            if ("GET".equals(method)) {
                if ("/api/world-stack".equals(path)) {
                    result = worldStackService.model();
                } else if ("/api/world-stack/timeline".equals(path)) {
                    result = worldStackService.timeline();
                } else if ("/api/world-stack/timeline/export".equals(path)) {
                    Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                    result = worldStackService.exportTimeline(query.get("id"));
                } else if ("/api/world-stack/timeline/snapshots".equals(path)) {
                    result = worldStackService.timelineSnapshots();
                } else if ("/api/world-stack/jobs".equals(path)) {
                    result = worldStackService.jobs();
                } else if (path.startsWith("/api/world-stack/jobs/")) {
                    String id = URLDecoder.decode(
                            path.substring("/api/world-stack/jobs/".length()),
                            StandardCharsets.UTF_8);
                    result = worldStackService.job(id);
                } else {
                    status = 404;
                    result = Map.of("error", "Not Found");
                }
            } else if ("POST".equals(method)) {
                switch (path) {
                    case "/api/world-stack/import/preview" ->
                            result = worldStackService.previewImport(body);
                    case "/api/world-stack/import/cancel" ->
                            result = worldStackService.cancelImport(body);
                    case "/api/world-stack/import" ->
                            result = worldStackService.importWorld(body);
                    case "/api/world-stack/reorder" ->
                            result = worldStackService.reorder(body);
                    case "/api/world-stack/archive/preview" ->
                            result = worldStackService.previewArchive(body);
                    case "/api/world-stack/archive" ->
                            result = worldStackService.archive(body);
                    case "/api/world-stack/icon" ->
                            result = worldStackService.saveIcon(body);
                    case "/api/world-stack/icon/delete" ->
                            result = worldStackService.deleteIcon(body);
                    case "/api/world-stack/timeline/preview" ->
                            result = worldStackService.previewTimelineRestore(body);
                    case "/api/world-stack/timeline/restore" ->
                            result = worldStackService.restoreTimeline(body);
                    case "/api/world-stack/timeline/cleanup" ->
                            result = worldStackService.cleanupTimelineSnapshot(body);
                    default -> {
                        status = 404;
                        result = Map.of("error", "Not Found");
                    }
                }
            } else {
                status = 405;
                result = Map.of("error", "Method Not Allowed");
            }
            if (status == 200 && result instanceof Map<?, ?> map
                    && Boolean.FALSE.equals(map.get("ok"))) {
                status = 400;
            }
            json(exchange, status, result);
        } catch (RuntimeException | LinkageError e) {
            status = 500;
            json(exchange, status, Map.of("error", "服务器内部错误：" + errorDetail(e)));
            LOG.warn("[web] 处理 {} 失败: {}", exchange.getRequestURI().getPath(), e.toString());
        } finally {
            log(exchange, status, start);
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            String key = split < 0 ? pair : pair.substring(0, split);
            String value = split < 0 ? "" : pair.substring(split + 1);
            try {
                key = URLDecoder.decode(key, StandardCharsets.UTF_8);
                value = URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            out.putIfAbsent(key, value);
        }
        return out;
    }

    private static Integer intParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String body) {
        Map<String, Object> map = GSON.fromJson(body, MAP_TYPE);
        return map == null ? Map.of() : map;
    }

    private void handleLottery(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (!authorized(exchange)) {
                status = 401;
                json(exchange, status, Map.of("error", "未授权：缺少或错误的 Bearer token"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/api/lottery".equals(path)) {
                json(exchange, 200, lotteryService.adminModel());
                return;
            }
            if ("GET".equals(method) && "/api/lottery/values".equals(path)) {
                json(exchange, 200, lotteryService.values());
                return;
            }
            if ("POST".equals(method) && "/api/lottery/draw".equals(path)) {
                json(exchange, 200, lotteryService.draw());
                return;
            }
            if ("POST".equals(method) && "/api/lottery/cancel".equals(path)) {
                json(exchange, 200, lotteryService.cancel());
                return;
            }
            status = 404;
            json(exchange, status, Map.of("error", "未知的乐透接口"));
        } finally {
            log(exchange, status, start);
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {        long start = System.currentTimeMillis();
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
        boolean allowed = WebSecurity.authorized(token, exchange.getRequestHeaders()
                .getFirst("Authorization"));
        if (allowed) WebAuditService.capture(exchange);
        return allowed;
    }

    private static String readBody(HttpExchange exchange) throws IOException, BodyTooLarge {
        return readBody(exchange, WebSecurity.MAX_BODY_BYTES);
    }

    private static String readBody(HttpExchange exchange, int maxBytes)
            throws IOException, BodyTooLarge {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            long length;
            try {
                length = Long.parseLong(declared);
            } catch (NumberFormatException e) {
                throw new BodyTooLarge();
            }
            if (!WebSecurity.bodyWithinLimit(length, maxBytes)) {
                throw new BodyTooLarge();
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
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

    /**
     * 本地面板排障用：把异常类型/消息带回前端，避免只看到“服务器内部错误”。
     * 只保留一行并截断，避免把超长堆栈或路径塞进响应。
     */
    private static String errorDetail(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        String singleLine = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (singleLine.length() > 300) {
            singleLine = singleLine.substring(0, 300) + "...";
        }
        return error.getClass().getSimpleName() + ": " + singleLine;
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
        securityHeaders(exchange);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * 公网只读/管理面板都可能被扫描：统一补浏览器侧防护头。
     * 脚本全部是同源外部文件，页面没有内联 script / innerHTML，所以 CSP 不需要放开脚本。
     */
    private static void securityHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        headers.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; "
                        + "style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data: blob:; connect-src 'self'; "
                        + "font-src 'self' data:; object-src 'none'; base-uri 'none'; "
                        + "form-action 'self'; frame-ancestors 'none'");
    }

    private static void sendFile(HttpExchange exchange, int status, String type, String name,
                                 Path file) throws IOException {
        long size = Files.size(file);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + name.replace("\"", "") + "\"");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        securityHeaders(exchange);
        exchange.sendResponseHeaders(status, size);
        try (OutputStream out = exchange.getResponseBody();
             InputStream in = Files.newInputStream(file)) {
            in.transferTo(out);
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
