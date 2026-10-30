package com.shenqianye.samsara.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** 本地 Web 的安全工具：常量时间 token 比较、Bearer 解析、路径白名单。 */
public final class WebSecurity {
    /** 请求体上限（256 KiB）：配置表单远小于此，超限直接拒绝。 */
    public static final int MAX_BODY_BYTES = 256 * 1024;

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
            case "/", "/index.html" -> "/web/index.html";
            case "/app.js" -> "/web/app.js";
            case "/identity", "/identity.html" -> "/web/identity.html";
            case "/identity.js" -> "/web/identity.js";
            case "/style.css" -> "/web/style.css";
            default -> null;
        };
    }

    /** 拒绝目录穿越/异常路径；只允许固定 API 路径。 */
    public static boolean safeApiPath(String path) {
        return "/api/config".equals(path) || "/api/reload".equals(path)
                || "/api/status".equals(path)
                || "/api/identity".equals(path)
                || "/api/identity/preview".equals(path)
                || "/api/identity/rebind".equals(path)
                || "/api/identity/reload".equals(path)
                || "/api/identity/audit".equals(path)
                || "/api/identity/generate".equals(path);
    }

    public static boolean bodyWithinLimit(long length) {
        return length >= 0 && length <= MAX_BODY_BYTES;
    }
}
