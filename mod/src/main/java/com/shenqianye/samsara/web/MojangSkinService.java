package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shenqianye.samsara.Samsara;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import javax.imageio.ImageIO;

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
    private final Map<String, Entry> headCache = new ConcurrentHashMap<>();

    /** 返回皮肤 PNG；没有正版档案、网络失败或响应异常时返回 null。 */
    public Skin skin(String uuid, String name) {
        String normalizedUuid = normalizeUuid(uuid);
        String normalizedName = normalizeName(name);
        String fallback = fallbackName(name);
        String key = cacheKey(normalizedUuid, normalizedName, fallback);
        if (key.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAt > now) {
            return cached.skin;
        }
        Skin skin = load(normalizedUuid, normalizedName, fallback);
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
        String fallback = fallbackName(name);
        String key = cacheKey(normalizedUuid, normalizedName, fallback);
        if (key.isEmpty()) return null;
        long now = System.currentTimeMillis();
        Entry cached = bodyCache.get(key);
        if (cached != null && cached.expiresAt > now) return cached.skin;
        // 全身渲染优先用玩家名（含非 ASCII 离线名）：离线服的 UUID 是本地生成的，
        // 第三方皮肤站按名字/ID 才能命中；Mojang 官方 API 仍只用规范化的 ASCII 名。
        String id = fallbackName(name) != null ? fallbackName(name)
                : normalizedName != null ? normalizedName : normalizedUuid;
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

    /**
     * 玩家列表/地图头像：直接返回从完整皮肤裁出的 8×8 头部（含第二层帽子），
     * 而不是整张 skin 展开图。离线玩家也走同一套按名字/ID 的皮肤查找。
     */
    public Skin head(String uuid, String name) {
        String normalizedUuid = normalizeUuid(uuid);
        String normalizedName = normalizeName(name);
        String fallback = fallbackName(name);
        String key = cacheKey(normalizedUuid, normalizedName, fallback);
        if (key.isEmpty()) return null;
        long now = System.currentTimeMillis();
        Entry cached = headCache.get(key);
        if (cached != null && cached.expiresAt > now) return cached.skin;
        Skin cropped = cropHead(skin(uuid, name));
        headCache.put(key, new Entry(now + (cropped == null ? FAILURE_TTL_MS : SUCCESS_TTL_MS), cropped));
        return cropped;
    }

    private Skin cropHead(Skin skin) {
        if (skin == null || skin.bytes() == null || skin.bytes().length == 0) return null;
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(skin.bytes()));
            if (source == null || source.getWidth() < 16 || source.getHeight() < 16) return null;
            BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = out.createGraphics();
            try {
                // 前脸 8×8；64×32 老皮肤同样从 (8,8) 开始。
                graphics.drawImage(source, 0, 0, 64, 64, 8, 8, 16, 16, null);
                // 64×64 皮肤的第二层 / 帽子区域。
                if (source.getHeight() >= 64 && source.getWidth() >= 64) {
                    graphics.drawImage(source, 0, 0, 64, 64, 40, 8, 48, 16, null);
                }
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(out, "png", bytes)) return null;
            return new Skin(bytes.toByteArray(), "image/png");
        } catch (Exception e) {
            Samsara.LOGGER.debug("[web] 头像裁剪失败: {}", e.getMessage());
            return null;
        }
    }

    private Skin load(String uuid, String name, String fallbackName) {
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
            // UUID 是离线 UUID 或该账号没有皮肤时，再按玩家名查一次正版档案；
            // 同一身份组的正版账号通常能用名字拿到真实皮肤，避免直接回退默认 Steve。
            if (name != null && id != null) {
                String byName = lookupUuid(name);
                if (byName != null && !byName.equals(id)) {
                    String skinUrl = lookupSkinUrl(byName);
                    if (skinUrl != null) {
                        Skin skin = downloadSkin(skinUrl, false);
                        if (skin != null) return skin;
                    }
                }
            }
        } catch (Exception e) {
            Samsara.LOGGER.debug("[web] Mojang 皮肤解析失败 {} / {}: {}",
                    uuid, name, e.getMessage());
        }
        // 官方查不到（离线玩家/正版档案隐藏/网络失败）时回退第三方皮肤站；
        // 仍只接受 PNG 魔数与大小校验，失败才让前端用 SVG。
        String fallbackKey = fallbackName != null ? fallbackName : id;
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

    /** 离线玩家名可能含中文/空格/符号，Mojang 官方接口不认，但第三方皮肤站能按 ID 命中。 */
    private static String fallbackName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) return null;
        return trimmed;
    }

    /**
     * 缓存键必须同时包含 UUID 和玩家名：只带 UUID 的请求失败时会把默认 Steve
     * 写入 uuid 键，随后带名字的请求会命中同一份缓存，真实皮肤就永远出不来。
     */
    private static String cacheKey(String uuid, String name, String fallback) {
        StringBuilder key = new StringBuilder();
        if (uuid != null) key.append("uuid:").append(uuid);
        if (name != null) {
            if (key.length() > 0) key.append('|');
            key.append("name:").append(name.toLowerCase(Locale.ROOT));
        }
        if (key.length() == 0 && fallback != null) {
            key.append("id:").append(fallback.toLowerCase(Locale.ROOT));
        }
        return key.toString();
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
