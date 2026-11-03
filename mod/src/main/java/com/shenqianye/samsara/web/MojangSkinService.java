package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shenqianye.samsara.Samsara;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 通过 Mojang 官方 API 解析玩家皮肤。
 *
 * <p>浏览器不能直接跨域读取 Mojang 的 session server，所以由本地 Web 服务代理：
 * 名字 → {@code api.mojang.com} 解析 UUID → {@code sessionserver.mojang.com} 取
 * textures 属性 → 只允许 {@code textures.minecraft.net} 皮肤图。结果短 TTL 缓存，
 * 网络不可达/离线玩家返回 {@code null}，前端保持本地 SVG 回退头像。
 */
public final class MojangSkinService {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern HEX_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}");
    private static final String PROFILE_URL =
            "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final String NAME_URL =
            "https://api.mojang.com/users/profiles/minecraft/";
    private static final long SUCCESS_TTL_MS = 6L * 60 * 60 * 1000;
    private static final long FAILURE_TTL_MS = 2L * 60 * 1000;
    private static final int MAX_SKIN_BYTES = 2 * 1024 * 1024;

    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** 返回皮肤 PNG；没有正版档案、网络失败或响应异常时返回 null。 */
    public Skin skin(String uuid, String name) {
        String normalizedUuid = normalizeUuid(uuid);
        String normalizedName = normalizeName(name);
        String key = normalizedUuid != null ? "uuid:" + normalizedUuid
                : normalizedName != null ? "name:" + normalizedName.toLowerCase(Locale.ROOT) : "";
        if (key.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAt > now) {
            return cached.skin;
        }
        Skin skin = load(normalizedUuid, normalizedName);
        cache.put(key, new Entry(now + (skin == null ? FAILURE_TTL_MS : SUCCESS_TTL_MS), skin));
        if (cached != null && cached.skin != null && skin == null) {
            // 短暂网络抖动时继续用上一次成功的皮肤，避免地图头像闪回本地图。
            cache.put(key, new Entry(now + FAILURE_TTL_MS, cached.skin));
            return cached.skin;
        }
        return skin;
    }

    private Skin load(String uuid, String name) {
        try {
            String id = uuid;
            if (id == null && name != null) {
                id = lookupUuid(name);
            }
            if (id == null) {
                return null;
            }
            String skinUrl = lookupSkinUrl(id);
            if (skinUrl == null) {
                return null;
            }
            return downloadSkin(skinUrl);
        } catch (Exception e) {
            Samsara.LOGGER.debug("[web] Mojang 皮肤解析失败 {} / {}: {}",
                    uuid, name, e.getMessage());
            return null;
        }
    }

    private String lookupUuid(String name) throws Exception {
        JsonObject json = getJson(NAME_URL
                + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
        if (json == null || !json.has("id")) {
            return null;
        }
        return normalizeUuid(json.get("id").getAsString());
    }

    private String lookupSkinUrl(String uuid) throws Exception {
        JsonObject profile = getJson(PROFILE_URL + uuid + "?unsigned=false");
        if (profile == null) {
            return null;
        }
        JsonArray properties = profile.getAsJsonArray("properties");
        if (properties == null) {
            return null;
        }
        for (JsonElement element : properties) {
            if (!element.isJsonObject()) continue;
            JsonObject property = element.getAsJsonObject();
            if (!"textures".equals(property.has("name")
                    ? property.get("name").getAsString() : "")) {
                continue;
            }
            if (!property.has("value")) continue;
            byte[] decoded = Base64.getDecoder().decode(property.get("value").getAsString());
            JsonObject textures = GSON.fromJson(
                    new String(decoded, StandardCharsets.UTF_8), JsonObject.class);
            if (textures == null || !textures.has("textures")) continue;
            JsonElement skin = textures.getAsJsonObject("textures").get("SKIN");
            if (skin != null && skin.isJsonObject() && skin.getAsJsonObject().has("url")) {
                return skin.getAsJsonObject().get("url").getAsString();
            }
        }
        return null;
    }

    private Skin downloadSkin(String url) throws Exception {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"textures.minecraft.net".equals(host) && !"textures.mojang.com".equals(host)) {
            return null;
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "samsara-web-skin/1.0")
                .GET()
                .build();
        HttpResponse<byte[]> response = CLIENT.send(request,
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length == 0
                || response.body().length > MAX_SKIN_BYTES) {
            return null;
        }
        String type = response.headers().firstValue("Content-Type").orElse("");
        if (!type.toLowerCase(Locale.ROOT).startsWith("image/png")) {
            return null;
        }
        return new Skin(response.body(), "image/png");
    }

    private JsonObject getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "samsara-web-skin/1.0")
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
            return null;
        }
        JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
        return json;
    }

    private static String normalizeName(String name) {
        String value = name == null ? "" : name.trim();
        return NAME.matcher(value).matches() ? value : null;
    }

    private static String normalizeUuid(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!HEX_UUID.matcher(value).matches()) {
            return null;
        }
        String compact = value.replace("-", "");
        try {
            UUID uuid = new UUID(
                    Long.parseUnsignedLong(compact.substring(0, 16), 16),
                    Long.parseUnsignedLong(compact.substring(16), 16));
            return uuid.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record Skin(byte[] bytes, String mime) {
    }

    private record Entry(long expiresAt, Skin skin) {
    }
}
