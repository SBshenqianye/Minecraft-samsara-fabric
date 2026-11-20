package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.shenqianye.samsara.config.spec.ConfigEditorService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.shenqianye.samsara.web.http.WebHttp.errorDetail;
import static com.shenqianye.samsara.web.http.WebHttp.intParam;
import static com.shenqianye.samsara.web.http.WebHttp.json;
import static com.shenqianye.samsara.web.http.WebHttp.parseQuery;
import static com.shenqianye.samsara.web.http.WebHttp.sendBytes;
import static com.shenqianye.samsara.web.http.WebHttp.sendFile;
import static com.shenqianye.samsara.web.http.WebHttp.sendText;

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
    private final ContainerWebService containerService;
    private final InventoryWebService inventoryService;
    private final ItemWebService itemService;
    private final TrackWebService trackService;
    private final StorageLayoutWebService storageLayoutService = new StorageLayoutWebService();
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
        this(service, identityService, outputService, permissionService, mapService,
                worldStackService, serverService, diffService, userService, lotteryService,
                null, token, port);
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
                          ContainerWebService containerService,
                          String token, int port) {
        this(service, identityService, outputService, permissionService, mapService,
                worldStackService, serverService, diffService, userService, lotteryService,
                containerService, null, token, port);
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
                          ContainerWebService containerService,
                          InventoryWebService inventoryService,
                          String token, int port) {
        this(service, identityService, outputService, permissionService, mapService,
                worldStackService, serverService, diffService, userService, lotteryService,
                containerService, inventoryService, null, token, port);
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
                          ContainerWebService containerService,
                          InventoryWebService inventoryService,
                          TrackWebService trackService,
                          String token, int port) {
        this(service, identityService, outputService, permissionService, mapService,
                worldStackService, serverService, diffService, userService, lotteryService,
                containerService, inventoryService, trackService, null, token, port);
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
                          ContainerWebService containerService,
                          InventoryWebService inventoryService,
                          TrackWebService trackService,
                          ItemWebService itemService,
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
        this.containerService = containerService;
        this.inventoryService = inventoryService;
        this.itemService = itemService;
        this.trackService = trackService;
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
        server.createContext("/api/announcements", this::handleAnnouncements);
        server.createContext("/api/agent", this::handleAgent);
        server.createContext("/api/reload", this::handleReload);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/storage", this::handleStorage);
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
        if (containerService != null) {
            server.createContext("/api/containers", this::handleContainers);
        }
        if (inventoryService != null) {
            server.createContext("/api/inventory", this::handleInventory);
        }
        if (itemService != null) {
            server.createContext("/api/items", this::handleItems);
        }
        if (trackService != null) {
            server.createContext("/api/track", this::handleTrack);
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
            status = StaticContentHandler.serve(exchange);
        } finally {
            log(exchange, status, start);
        }
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        new ConfigWebHandler(this, service, serverService).handle(exchange);
    }


    private void handleAnnouncements(HttpExchange exchange) throws IOException {
        new AnnouncementsWebHandler(this, serverService).handle(exchange);
    }


    private void handleReload(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            int auth = authorize(exchange);
            if (auth != 0) {
                status = auth;
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
        new IdentityWebHandler(this, identityService).handle(exchange);
    }

    private void handleOutput(HttpExchange exchange) throws IOException {
        new OutputWebHandler(this, outputService).handle(exchange);
    }

    private void handlePermissions(HttpExchange exchange) throws IOException {
        new PermissionsWebHandler(this, permissionService).handle(exchange);
    }


    /**
     * 用户端只读路由：不需要 token，也不接受任何写方法。
     * 只暴露公开状态、世界栈列表、地图与在线玩家；管理端路径不在这里注册。
     */
    private void handleUser(HttpExchange exchange) throws IOException {
        new UserWebHandler(userService).handle(exchange);
    }


    private void handleMap(HttpExchange exchange) throws IOException {
        new MapWebHandler(this, mapService).handle(exchange);
    }

    private void handleDiff(HttpExchange exchange) throws IOException {
        new DiffWebHandler(this, diffService).handle(exchange);
    }


    private void handleServer(HttpExchange exchange) throws IOException {
        new ServerWebHandler(this, serverService).handle(exchange);
    }


    private void handleAudit(HttpExchange exchange) throws IOException {
        new AuditWebHandler(this).handle(exchange);
    }


    private void handleWorldStack(HttpExchange exchange) throws IOException {
        new WorldStackWebHandler(this, worldStackService).handle(exchange);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String body) {
        Map<String, Object> map = GSON.fromJson(body, MAP_TYPE);
        return map == null ? Map.of() : map;
    }

    private void handleLottery(HttpExchange exchange) throws IOException {
        new LotteryWebHandler(this, lotteryService).handle(exchange);
    }

    private void handleContainers(HttpExchange exchange) throws IOException {
        new ContainersWebHandler(this, containerService).handle(exchange);
    }

    private void handleTrack(HttpExchange exchange) throws IOException {
        new TrackWebHandler(this, trackService).handle(exchange);
    }


    private void handleInventory(HttpExchange exchange) throws IOException {
        new InventoryWebHandler(this, inventoryService).handle(exchange);
    }


    private void handleItems(HttpExchange exchange) throws IOException {
        new ItemsWebHandler(this, itemService).handle(exchange);
    }


    private void handleStatus(HttpExchange exchange) throws IOException {        long start = System.currentTimeMillis();
        int status = 200;
        try {
            int auth = authorize(exchange);
            if (auth != 0) {
                status = auth;
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

    /** 只读存储布局：每类数据在哪里、多大、是否随周目/备份。 */
    private void handleStorage(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            int auth = authorize(exchange);
            if (auth != 0) {
                status = auth;
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                status = 405;
                json(exchange, status, Map.of("error", "Method Not Allowed"));
                return;
            }
            json(exchange, 200, storageLayoutService.model());
        } catch (RuntimeException e) {
            status = 500;
            json(exchange, status, Map.of("error", errorDetail(e)));
        } finally {
            log(exchange, status, start);
        }
    }

    // ---- 工具 ----

    Path serverRoot() {
        if (serverService != null) {
            return serverService.serverRoot();
        }
        return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .toAbsolutePath().normalize();
    }

    /**
     * 鉴权 + scope + 限流 + 幂等重放。返回 0 表示继续处理；非 0 时响应已发送，
     * 调用方只需把状态码写入日志并返回。
     */
    int authorize(HttpExchange exchange) throws IOException {
        AgentAuthService.AuthContext context = AgentAuthService.getInstance().authenticate(
                exchange.getRequestHeaders().getFirst("Authorization"), token);
        if (context == null) {
            json(exchange, 401, Map.of("error", "未授权：缺少或错误的 Bearer token"));
            return 401;
        }
        exchange.setAttribute(AgentAuthService.ATTR_CONTEXT, context);
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/api/agent/tokens") && !context.admin()) {
            json(exchange, 403, Map.of("error", "token 管理仅管理员可用"));
            return 403;
        }
        AgentScope required = ApiScope.required(exchange.getRequestMethod(), path);
        if (!context.has(required)) {
            json(exchange, 403, Map.of("error", "token 缺少 scope：" + required.id()));
            return 403;
        }
        if (!AgentAuthService.getInstance().allow(context, required)) {
            exchange.getResponseHeaders().set("Retry-After", "60");
            json(exchange, 429, Map.of("error", "请求过于频繁，请稍后再试"));
            return 429;
        }
        IdempotencyService idempotency = IdempotencyService.getInstance();
        idempotency.attach(exchange, context.actor());
        IdempotencyService.Cached cached = idempotency.replay(exchange);
        if (cached != null) {
            exchange.getResponseHeaders().set("Idempotency-Replayed", "true");
            sendBytes(exchange, cached.status(), "application/json; charset=utf-8",
                    cached.body().getBytes(StandardCharsets.UTF_8));
            return cached.status();
        }
        WebAuditService.capture(exchange);
        return 0;
    }

    private void handleAgent(HttpExchange exchange) throws IOException {
        new AgentWebHandler(this).handle(exchange);
    }


    static String readBody(HttpExchange exchange) throws IOException, BodyTooLarge {
        return readBody(exchange, WebSecurity.MAX_BODY_BYTES);
    }

    static String readBody(HttpExchange exchange, int maxBytes)
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

    /** 路由处理器记录失败（不把堆栈发给前端，只写服务端日志）。 */
    void warnRoute(HttpExchange exchange, RuntimeException error) {
        LOG.warn("[web] 处理 {} 失败: {}", exchange.getRequestURI().getPath(), error.getMessage());
    }

    static void log(HttpExchange exchange, int status, long startMs) {        URI uri = exchange.getRequestURI();
        // 只记录方法/路径/状态码/耗时；不记录 token，也不记录查询串（避免泄漏）。
        LOG.debug("[web] {} {} → {} ({}ms)", exchange.getRequestMethod(), uri.getPath(),
                status, System.currentTimeMillis() - startMs);
    }

    static final class BodyTooLarge extends Exception {
    }
}




