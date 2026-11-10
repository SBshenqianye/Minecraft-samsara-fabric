package com.shenqianye.samsara.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** 本地 Web 的安全工具：常量时间 token 比较、Bearer 解析、路径白名单。 */
public final class WebSecurity {
    /** 请求体上限（256 KiB）：配置表单远小于此，超限直接拒绝。 */
    public static final int MAX_BODY_BYTES = 256 * 1024;
    /** 栈层图标上传单独放宽到 1 MiB（base64 内嵌在 JSON 里）。 */
    public static final int MAX_ICON_BODY_BYTES = 1024 * 1024;

    private WebSecurity() {
    }

    /** 常量时间比较；长度不同也先比较哈希，避免明显的时序泄漏。 */
    public static boolean tokenEquals(String expected, String supplied) {
        if (expected == null || supplied == null || expected.isEmpty()) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = supplied.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /**
     * 解析 {@code Authorization: Bearer <token>}。
     * 不接受 URL 查询参数传 token（避免访问日志泄漏）。
     */
    public static String bearerToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        int space = value.indexOf(' ');
        if (space <= 0 || !value.substring(0, space).toLowerCase(Locale.ROOT).equals("bearer")) {
            return null;
        }
        String token = value.substring(space + 1).trim();
        return token.isEmpty() ? null : token;
    }

    /** 鉴权：Authorization 头必须匹配。 */
    public static boolean authorized(String expected, String authorization) {
        return tokenEquals(expected, bearerToken(authorization));
    }

    /** 静态资源路径白名单（固定路径，不做任何路径拼接）。 */
    public static String staticResource(String path) {
        return switch (path) {
            case "/", "/index.html", "/user", "/containers",
                 "/config", "/config-editor", "/claim-range", "/identity", "/output",
                 "/permissions", "/map", "/announcements", "/world-stack", "/lottery",
                 "/server", "/server/config", "/server/backup", "/server/console",
                 "/server/audit",
                 "/vanilla", "/vanilla/config", "/vanilla/backup", "/vanilla/console",
                 "/vanilla/audit",
                 "/samsara", "/samsara/economy", "/samsara/economy/overview",
                 "/samsara/economy/current", "/samsara/economy/values",
                 "/samsara/economy/fund", "/samsara/economy/fund-inventory",
                 "/samsara/economy/insurance",
                 "/samsara/economy/history", "/samsara/economy/lottery",
                 "/samsara/sky", "/samsara/sky/track", "/samsara/sky/diff",
                 "/samsara/sky/containers", "/samsara/sky/inventory",
                 "/samsara/sky/web-inventory", "/identity-inventory",
                 "/samsara/config", "/samsara/config/editor", "/samsara/config/advanced",
                 "/samsara/config/claim-range", "/samsara/config/output",
                 "/samsara/config/permissions",
                 "/samsara/world", "/samsara/world/map", "/samsara/world/stack",
                 "/samsara/community", "/samsara/community/announcements",
                 "/samsara/community/identity",
                 "/samsara/system", "/samsara/system/audit",
                 "/track", "/diff", "/inventory" -> "/web/vue/index.html";
            case "/legacy.html" -> "/web/index.html";
            case "/legacy-user.html" -> "/web/user.html";
            case "/user.html" -> "/web/user.html";
            case "/user.js" -> "/web/user.js";
            case "/lottery.html" -> "/web/lottery.html";
            case "/lottery.js" -> "/web/lottery.js";
            case "/item-ui.js" -> "/web/item-ui.js";
            case "/value-table.js" -> "/web/value-table.js";
            case "/app.js" -> "/web/app.js";
            case "/page-kit.js" -> "/web/page-kit.js";
            case "/config-editor.js" -> "/web/config-editor.js";
            case "/chat-text.js" -> "/web/chat-text.js";
            case "/announcements.js" -> "/web/announcements.js";
            case "/openapi.json" -> "/web/openapi.json";
            case "/identity.html" -> "/web/identity.html";
            case "/identity.js" -> "/web/identity.js";
            case "/structured.js" -> "/web/structured.js";
            case "/form-kit.js" -> "/web/form-kit.js";
            case "/particles.js" -> "/web/particles.js";
            case "/output.js" -> "/web/output.js";
            case "/permissions.js" -> "/web/permissions.js";
            case "/banners.js" -> "/web/banners.js";
            case "/map.js" -> "/web/map.js";
            case "/world-stack.js" -> "/web/world-stack.js";
            case "/server.js" -> "/web/server.js";
            case "/style.css" -> "/web/style.css";
            default -> {
                if (path.matches("^/mods/[A-Za-z0-9_.-]+(/[A-Za-z0-9_./-]+)?$")
                        && !path.contains("..")) {
                    yield "/web/vue/index.html";
                }
                if (path.matches("^/items/[a-z0-9_]+\\.png$")) {
                    yield "/web" + path;
                }
                if (path.startsWith("/vue/") && path.matches("^/vue/[A-Za-z0-9_./-]+$")
                        && !path.contains("..")) {
                    yield "/web" + path;
                }
                yield null;
            }
        };
    }

    /** 拒绝目录穿越/异常路径；只允许固定 API 路径。 */
    public static boolean safeApiPath(String path) {
        return "/api/user/status".equals(path)
                || "/api/user/worlds".equals(path)
                || "/api/user/lottery".equals(path)
                || "/api/user/lottery/values".equals(path)
                || "/api/user/map".equals(path)
                || "/api/user/map/players".equals(path)
                || "/api/user/map/live".equals(path)
                || "/api/user/map/tile".equals(path)
                || "/api/user/map/avatar".equals(path)
                || "/api/user/worlds/icon".equals(path)
                || "/api/config".equals(path) || "/api/reload".equals(path)
                || "/api/status".equals(path)
                || "/api/identity".equals(path)
                || "/api/identity/preview".equals(path)
                || "/api/identity/rebind".equals(path)
                || "/api/identity/reload".equals(path)
                || "/api/identity/audit".equals(path)
                || "/api/identity/generate".equals(path)
                || "/api/identity/resolve".equals(path)
                || "/api/identity/data".equals(path)
                || "/api/identity/data/backup".equals(path)
                || "/api/output".equals(path)
                || "/api/output/preview".equals(path)
                || "/api/output/reload".equals(path)
                || "/api/permissions".equals(path)
                || "/api/permissions/reload".equals(path)
                || "/api/map".equals(path)
                || "/api/map/players".equals(path)
                || "/api/map/live".equals(path)
                || "/api/map/tile".equals(path)
                || "/api/diff".equals(path)
                || "/api/diff/chunk".equals(path)
                || "/api/diff/chunk/restore".equals(path)
                || "/api/diff/view".equals(path)
                || "/api/diff/view/status".equals(path)
                || "/api/config/retention-estimate".equals(path)
                || "/api/config/pages".equals(path)
                || "/api/config/preview".equals(path)
                || "/api/announcements".equals(path)
                || "/api/announcements/preview".equals(path)
                || "/api/announcements/send".equals(path)
                || "/api/announcements/stop".equals(path)
                || "/api/agent/whoami".equals(path)
                || "/api/agent/tokens".equals(path)
                || "/api/world-stack".equals(path)
                || "/api/world-stack/timeline".equals(path)
                || "/api/world-stack/timeline/preview".equals(path)
                || "/api/world-stack/timeline/restore".equals(path)
                || "/api/world-stack/timeline/export".equals(path)
                || "/api/world-stack/timeline/snapshots".equals(path)
                || "/api/world-stack/timeline/cleanup".equals(path)
                || path.startsWith("/api/world-stack/import")
                || path.startsWith("/api/world-stack/reorder")
                || path.startsWith("/api/world-stack/archive")
                || path.startsWith("/api/world-stack/icon")
                || path.startsWith("/api/world-stack/jobs")
                || "/api/server".equals(path)
                || path.startsWith("/api/server/")
                || "/api/lottery".equals(path)
                || "/api/lottery/draw".equals(path)
                || "/api/lottery/cancel".equals(path)
                || "/api/lottery/fund".equals(path)
                || "/api/lottery/fund/write".equals(path)
                || "/api/containers".equals(path)
                || "/api/containers/read".equals(path)
                || "/api/containers/write".equals(path)
                || "/api/containers/item".equals(path)
                || "/api/containers/scan".equals(path)
                || "/api/containers/offline".equals(path)
                || "/api/inventory".equals(path)
                || "/api/inventory/write".equals(path)
                || "/api/inventory/item".equals(path)
                || "/api/inventory/web".equals(path)
                || "/api/inventory/web/write".equals(path)
                || "/api/inventory/web/deposit".equals(path)
                || "/api/inventory/web/withdraw".equals(path)
                || "/api/items".equals(path)
                || "/api/items/schema".equals(path)
                || "/api/items/describe".equals(path)
                || "/api/items/raw".equals(path)
                || "/api/items/build".equals(path)
                || "/api/track".equals(path)
                || "/api/track/list".equals(path)
                || "/api/audit".equals(path)
                || "/api/audit/preview".equals(path)
                || "/api/audit/rollback".equals(path);
    }

    public static boolean bodyWithinLimit(long length) {
        return bodyWithinLimit(length, MAX_BODY_BYTES);
    }

    public static boolean bodyWithinLimit(long length, int maxBytes) {
        return length >= 0 && length <= maxBytes;
    }
}
