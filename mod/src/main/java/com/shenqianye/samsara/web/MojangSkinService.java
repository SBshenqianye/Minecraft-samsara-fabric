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
    /** 官方不可用时的第三方皮肤站回退（按名字或 UUID 取完整皮肤）。 */
    private static final String[] FALLBACK_SKIN_URLS = {
            "https://mc-heads.net/skin/",
            "https://minotar.net/skin/"
    };
    private static final long SUCCESS_TTL_MS = 6L * 60 * 60 * 1000;
    private static final long FAILURE_TTL_MS = 2L * 60 * 1000;
    private static final int MAX_SKIN_BYTES = 2 * 1024 * 1024;

    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Entry> bodyCache = new ConcurrentHashMap<>();

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

    /** 第三方渲染的全身 3D 纸盒人（与实际皮肤一致）；失败时前端回退头像。 */
    public Skin body(String uuid, String name) {
        String normalizedUuid = normalizeUuid(uuid);
        String normalizedName = normalizeName(name);
        String key = normalizedUuid != null ? "uuid:" + normalizedUuid
                : normalizedName != null ? "name:" + normalizedName.toLowerCase(Locale.ROOT) : "";
        if (key.isEmpty()) return null;
        long now = System.currentTimeMillis();
        Entry cached = bodyCache.get(key);
        if (cached != null && cached.expiresAt > now) return cached.skin;
        // 全身渲染优先用玩家名：离线服的 UUID 是本地生成的，第三方皮肤站按名字才能命中。
        String id = normalizedName != null ? normalizedName : normalizedUuid;
        String encoded = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
        Skin skin = null;
        for (String base : new String[]{"https://minotar.net/body/", "https://mc-heads.net/body/"}) {
            try {
                skin = downloadSkin(base + encoded + "/240", true);
                if (skin != null) break;
            } catch (Exception e) {
                Samsara.LOGGER.debug("[web] 3D 皮肤渲染失败 {}: {}", base, e.getMessage());
            }
        }
        bodyCache.put(key, new Entry(now + (skin == null ? FAILURE_TTL_MS : SUCCESS_TTL_MS), skin));
        return skin;
    }

    private Skin load(String uuid, String name) {
        String id = uuid;
        try {
            if (id == null && name != null) {
                id = lookupUuid(name);
            }
            if (id != null) {
                String skinUrl = lookupSkinUrl(id);
                if (skinUrl != null) {
                    Skin skin = downloadSkin(skinUrl, false);
                    if (skin != null) return skin;
                }
            }
        } catch (Exception e) {
            Samsara.LOGGER.debug("[web] Mojang 皮肤解析失败 {} / {}: {}",
                    uuid, name, e.getMessage());
        }
        // 官方查不到（离线玩家/正版档案隐藏/网络失败）时回退第三方皮肤站；
        // 仍只接受 PNG 魔数与大小校验，失败才让前端用 SVG。
        String fallbackKey = name != null ? name : id;
        if (fallbackKey != null) {
            for (String base : FALLBACK_SKIN_URLS) {
                try {
                    Skin skin = downloadSkin(base
                            + URLEncoder.encode(fallbackKey, StandardCharsets.UTF_8)
                            .replace("+", "%20"), true);
                    if (skin != null) return skin;
                } catch (Exception e) {
                    Samsara.LOGGER.debug("[web] 第三方皮肤回退失败 {}: {}", base, e.getMessage());
                }
            }
        }
        return null;
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
        return downloadSkin(url, false);
    }

    private Skin downloadSkin(String url, boolean fallbackHost) throws Exception {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = "textures.minecraft.net".equals(host)
                || "textures.mojang.com".equals(host)
                || (fallbackHost && ("mc-heads.net".equals(host) || "minotar.net".equals(host)));
        if (!allowed) {
            return null;
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "samsara-web-skin/1.0")
                .GET()
                .build();
        HttpResponse<byte[]> response = CLIENT.send(request,
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length < 8
                || response.body().length > MAX_SKIN_BYTES) {
            return null;
        }
        byte[] body = response.body();
        // PNG 魔数：89 50 4E 47 0D 0A 1A 0A；防止第三方返回 HTML/错误页。
        if ((body[0] & 0xFF) != 0x89 || body[1] != 'P' || body[2] != 'N' || body[3] != 'G') {
            return null;
        }
        return new Skin(body, "image/png");
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
