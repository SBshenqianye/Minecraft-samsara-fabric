package com.shenqianye.samsara.web;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.season.StackLayer;
import com.shenqianye.samsara.season.StackManifest;
import com.shenqianye.samsara.season.StackPaths;
import com.shenqianye.samsara.storage.StoragePaths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 世界栈卡片图标：管理员上传图、存档自带 {@code icon.png}、以及无图时的地图回退。
 *
 * <p>上传只写 {@code world/samsara/stack-icons/<stackId>.<ext>}，先写临时文件再原子替换；
 * 失败不会破坏旧图。接口只接受 PNG/JPEG/WebP，并同时校验 MIME 与文件头，拒绝 SVG/HTML。
 * 不写 {@code config/}，也不修改世界数据。
 */
public final class WorldStackIconService {
    public static final int MAX_ICON_BYTES = 1024 * 1024;
    private static final Pattern STACK_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final List<String> EXTENSIONS = List.of("png", "jpg", "jpeg", "webp");
    private static final String DEFAULT_URL_BASE = "/api/user/worlds/icon";
    /**
     * Mojang 原版默认图标（客户端 26.3 的 textures/misc/unknown_server.png，128×128）。
     * 该文件用于没有自定义世界图标的存档；除此之外再用灰白/低对比度启发式兜底。
     */
    private static final Set<String> KNOWN_DEFAULT_HASHES = Set.of(
            "b0b745c4573737e150db7b880fc01321a9e1f96180622b2c6200ea3944961690");

    private final Path worldRoot;

    public WorldStackIconService() {
        this(StoragePaths.get().worldRoot());
    }

    WorldStackIconService(Path worldRoot) {
        this.worldRoot = worldRoot.toAbsolutePath().normalize();
    }

    /** 返回给前端的白名单图标信息；不包含绝对路径。 */
    public Map<String, Object> describe(String stackId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Resolved resolved = resolve(stackId);
        if (resolved == null) {
            out.put("source", "map");
            out.put("custom", false);
            return out;
        }
        out.put("source", resolved.source);
        out.put("custom", "upload".equals(resolved.source));
        out.put("mime", resolved.mime);
        out.put("version", resolved.version);
        out.put("url", DEFAULT_URL_BASE + "?stack=" + stackId + "&v=" + resolved.version);
        return out;
    }

    /** 读取当前生效图标字节；没有自定义/存档图标时返回 null，由前端回退地图。 */
    public IconFile read(String stackId) {
        Resolved resolved = resolve(stackId);
        if (resolved == null) {
            return null;
        }
        try {
            return new IconFile(Files.readAllBytes(resolved.file), resolved.mime,
                    resolved.source, resolved.version);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[web] 读取栈层图标失败 {}: {}", resolved.file, e.getMessage());
            return null;
        }
    }

    /** 上传/替换图标。失败时保留原文件。 */
    public Map<String, Object> save(String stackId, String mime, String data) {
        String id = stackId == null ? "" : stackId.trim();
        if (!STACK_ID.matcher(id).matches()) {
            return error("栈层 id 非法");
        }
        StackLayer layer = findLayer(id);
        if (layer == null) {
            return error("栈层不存在：" + id);
        }
        String normalized = normalizeMime(mime);
        if (!"image/png".equals(normalized) && !"image/jpeg".equals(normalized)
                && !"image/webp".equals(normalized)) {
            return error("只支持 PNG / JPEG / WebP 图片");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data == null ? "" : data);
        } catch (IllegalArgumentException e) {
            return error("图标数据不是合法的 base64");
        }
        if (bytes.length == 0) {
            return error("图标数据为空");
        }
        if (bytes.length > MAX_ICON_BYTES) {
            return error("图标解码后超过 1 MiB");
        }
        String detected = detectMime(bytes);
        if (!normalized.equals(detected)) {
            return error("文件头与声明类型不一致；不允许 SVG/HTML 伪装成图片");
        }
        String ext = extension(normalized);
        Path dir = iconsDir();
        Path target = dir.resolve(id + "." + ext);
        Path temp = null;
        try {
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, id + "-", ".upload");
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
            // 切换格式时清掉旧扩展名；失败不影响刚写入的新图。
            for (String other : EXTENSIONS) {
                Path stale = dir.resolve(id + "." + other);
                if (!stale.equals(target)) {
                    try {
                        Files.deleteIfExists(stale);
                    } catch (IOException ignored) {
                        // 旧文件删不掉时保留，读取时仍按 png/jpg/webp 顺序取第一个。
                    }
                }
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[web] 写入栈层图标失败 {}: {}", target, e.getMessage());
            return error("写入图标失败");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("icon", describe(id));
        return out;
    }

    /** 删除管理员上传图，回退到存档 icon.png / 地图缩略图。 */
    public Map<String, Object> delete(String stackId) {
        String id = stackId == null ? "" : stackId.trim();
        if (!STACK_ID.matcher(id).matches() || findLayer(id) == null) {
            return error("栈层不存在：" + id);
        }
        Path dir = iconsDir();
        for (String ext : EXTENSIONS) {
            try {
                Files.deleteIfExists(dir.resolve(id + "." + ext));
            } catch (IOException e) {
                Samsara.LOGGER.warn("[web] 删除栈层自定义图标失败 {}: {}", id, e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("icon", describe(id));
        return out;
    }

    public Path iconsDir() {
        return worldRoot.resolve("samsara").resolve("stack-icons");
    }

    private Resolved resolve(String stackId) {
        if (stackId == null || !STACK_ID.matcher(stackId).matches()) {
            return null;
        }
        StackLayer layer = findLayer(stackId);
        if (layer == null) {
            return null;
        }
        Path custom = customFile(stackId);
        if (custom != null) {
            return resolved(custom, "upload", mimeOf(custom));
        }
        for (Path candidate : saveIconCandidates(layer)) {
            if (!Files.isRegularFile(candidate) || !sizeAtMost(candidate)
                    || isDefaultIcon(candidate)) {
                continue;
            }
            if (!"image/png".equals(detectMime(readHeader(candidate)))) {
                continue;
            }
            return resolved(candidate, "save", "image/png");
        }
        return null;
    }

    private Resolved resolved(Path file, String source, String mime) {
        try {
            String version = Files.getLastModifiedTime(file).toMillis() + "-" + Files.size(file);
            return new Resolved(file, mime, source, version);
        } catch (IOException e) {
            return null;
        }
    }

    private Path customFile(String stackId) {
        Path dir = iconsDir();
        for (String ext : EXTENSIONS) {
            Path file = dir.resolve(stackId + "." + ext);
            if (Files.isRegularFile(file) && sizeAtMost(file)
                    && detectMime(readHeader(file)) != null) {
                return file;
            }
        }
        return null;
    }

    private static boolean sizeAtMost(Path file) {
        try {
            return Files.size(file) <= MAX_ICON_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private StackLayer findLayer(String stackId) {
        StackManifest manifest = StackManifest.load(worldRoot);
        return manifest.byId(stackId);
    }

    private List<Path> saveIconCandidates(StackLayer layer) {
        List<Path> out = new ArrayList<>();
        if (layer.top) {
            out.add(worldRoot.resolve("icon.png"));
        } else {
            out.add(StackPaths.layerMeta(worldRoot, layer).resolve("icon.png"));
            out.add(StackPaths.layerDimensions(worldRoot, layer).resolve("icon.png"));
            out.add(worldRoot.resolve(layer.id).resolve("icon.png"));
        }
        return out;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }

    private static String normalizeMime(String mime) {
        String value = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
        return "image/jpg".equals(value) ? "image/jpeg" : value;
    }

    private static String extension(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
    }

    private static String mimeOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    /** 只读文件头，避免为判断类型把大图整体读进内存。 */
    private static byte[] readHeader(Path file) {
        try (var in = Files.newInputStream(file)) {
            return in.readNBytes(12);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    static String detectMime(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && bytes[4] == 0x0d && bytes[5] == 0x0a
                && bytes[6] == 0x1a && bytes[7] == 0x0a) {
            return "image/png";
        }
        if ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E'
                && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }

    /**
     * 识别 Minecraft 默认的灰白世界图标：先匹配客户端原版 {@code unknown_server.png}
     * 的 SHA-256，再对 64×64/128×128 的近似灰度、低对比度图做启发式兜底。
     */
    static boolean isDefaultIcon(Path file) {
        try {
            if (KNOWN_DEFAULT_HASHES.contains(sha256(file))) {
                return true;
            }            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                return true;
            }
            if (!((image.getWidth() == 64 && image.getHeight() == 64)
                    || (image.getWidth() == 128 && image.getHeight() == 128))) {
                return false;
            }
            long count = 0;
            long colored = 0;
            double sum = 0;
            double sumSquares = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >>> 16) & 0xff;
                    int g = (rgb >>> 8) & 0xff;
                    int b = rgb & 0xff;
                    int max = Math.max(r, Math.max(g, b));
                    int min = Math.min(r, Math.min(g, b));
                    if (max - min > 10) {
                        colored++;
                    }
                    double luminance = 0.299 * r + 0.587 * g + 0.114 * b;
                    sum += luminance;
                    sumSquares += luminance * luminance;
                    count++;
                }
            }
            if (count == 0) {
                return true;
            }
            double mean = sum / count;
            double variance = Math.max(0, sumSquares / count - mean * mean);
            return colored <= count * 0.01 && Math.sqrt(variance) < 30
                    && mean > 35 && mean < 235;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    public record IconFile(byte[] bytes, String mime, String source, String version) {
    }

    private record Resolved(Path file, String mime, String source, String version) {
    }
}
