package com.shenqianye.samsara.web;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

/** 本地 Web 面板的生命周期：SERVER_STARTED 启动，SERVER_STOPPING 停止。 */
public final class WebLifecycle {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static LocalWebServer server;

    private WebLifecycle() {
    }

    public static synchronized void start() {
        if (server != null) {
            return;
        }
        ConfigManager config = ConfigManager.getInstance();
        if (!config.bool(ConfigSchema.WEB, "enabled")) {
            Samsara.LOGGER.info("[web] web.yml enabled=false，本地配置面板未启动");
            return;
        }
        String token = config.string(ConfigSchema.WEB, "token");
        if (token == null || token.isBlank()) {
            token = generateToken();
            var result = config.save(ConfigSchema.WEB, Map.of("token", token));
            if (!result.ok()) {
                Samsara.LOGGER.warn("[web] token 写入 web.yml 失败：{}", result.errors());
                return;
            }
        }
        int port = config.integer(ConfigSchema.WEB, "port");
        LocalWebServer candidate = new LocalWebServer(new WebConfigService(), token, port);
        if (!candidate.start()) {
            return; // 端口占用：WARN 已记录，不阻塞服务器
        }
        server = candidate;
        // 启动后单独输出一块“可复制链接”，方便服主在服务器本机快速打开。
        // token 不放进 URL，避免被浏览器历史/日志记录；仍写在 web.yml 并单独打印。
        String url = "http://" + LocalWebServer.BIND_ADDRESS + ":" + candidate.port() + "/";
        Samsara.LOGGER.info("[web] ============================================================");
        Samsara.LOGGER.info("[web] 本地配置面板已就绪：{}", url);
        Samsara.LOGGER.info("[web] 访问 token：{}", token);
        Samsara.LOGGER.info("[web] 在服务器本机浏览器打开上面的链接，粘贴 token 即可；仅回环访问");
        Samsara.LOGGER.info("[web] ============================================================");
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
