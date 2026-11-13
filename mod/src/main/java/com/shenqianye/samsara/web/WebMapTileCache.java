package com.shenqianye.samsara.web;

import com.shenqianye.samsara.Samsara;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Web 地图 tile 的磁盘缓存。
 *
 * <p>路径固定为 {@code world/samsara/web-map-cache/<stackId>/<dimensionPath>/<scale>/<shade>/}，
 * 文件名带 region 文件的 mtime 与 size：region 变化后 key 必然不同，旧图不会被复用。
 * 这个目录是可安全删除的派生数据，不属于 claims/track/diff。
 *
 * <p>内存只保留少量热 tile；磁盘按总字节数做 LRU 淘汰（默认 512MB）。
 */
final class WebMapTileCache {
    static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024L;
    private static final int MEMORY_ENTRIES = 96;
    private static final String RENDER_VERSION = "v6";
    private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9_.-]");

    private final Path root;
    private final long maxBytes;
    private final Map<String, byte[]> memory = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MEMORY_ENTRIES;
                }
            });
    /** path → 字节数；访问顺序 = LRU 顺序。 */
    private final LinkedHashMap<Path, Long> files = new LinkedHashMap<>(64, 0.75f, true);
    private long bytes;
    private boolean scanned;
    private long hits;
    private long misses;
    private long writes;
    private long evictions;
    private long staleDeletes;

    WebMapTileCache(Path root) {
        this(root, DEFAULT_MAX_BYTES);
    }

    WebMapTileCache(Path root, long maxBytes) {
        this.root = root.toAbsolutePath().normalize();
        this.maxBytes = Math.max(1L, maxBytes);
    }

    Path root() {
        return root;
    }

    long maxBytes() {
        return maxBytes;
    }

    /** 读缓存；未命中返回 null。 */
    synchronized byte[] get(String stackId, String dimensionKey, int regionX, int regionZ,
                            int scale, boolean shading, long mtime, long size) {
        String key = memoryKey(stackId, dimensionKey, regionX, regionZ, scale, shading, mtime, size);
        byte[] cached = memory.get(key);
        if (cached != null) {
            hits += 1;
            return cached;
        }
        ensureScanned();
        Path path = pathFor(stackId, dimensionKey, regionX, regionZ, scale, shading, mtime, size);
        if (!Files.isRegularFile(path)) {
            misses += 1;
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) {
                Files.deleteIfExists(path);
                files.remove(path);
                misses += 1;
                return null;
            }
            memory.put(key, bytes);
            touch(path);
            hits += 1;
            return bytes;
        } catch (IOException e) {
            Samsara.LOGGER.debug("[web] 读取地图缓存失败 {}: {}", path, e.getMessage());
            misses += 1;
            return null;
        }
    }

    /** 写入缓存；失败只记 debug，不影响在线渲染。 */
    synchronized void put(String stackId, String dimensionKey, int regionX, int regionZ,
                          int scale, boolean shading, long mtime, long size, byte[] png) {
        if (png == null || png.length == 0) {
            return;
        }
        String key = memoryKey(stackId, dimensionKey, regionX, regionZ, scale, shading, mtime, size);
        ensureScanned();
        memory.put(key, png);
        Path path = pathFor(stackId, dimensionKey, regionX, regionZ, scale, shading, mtime, size);
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, png);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                // 某些文件系统/目标已存在时 ATOMIC_MOVE 会失败；退回普通替换。
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            Long previous = files.put(path, (long) png.length);
            bytes += png.length - (previous == null ? 0L : previous);
            writes += 1;
            deleteStaleSiblings(path, regionX, regionZ);
            evictIfNeeded();
        } catch (IOException e) {
            Samsara.LOGGER.debug("[web] 写入地图缓存失败 {}: {}", path, e.getMessage());
        }
    }

    Map<String, Object> stats() {
        synchronized (this) {
            ensureScanned();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dir", root.toString());
            out.put("maxBytes", maxBytes);
            out.put("bytes", bytes);
            out.put("files", files.size());
            out.put("memoryEntries", memory.size());
            out.put("hits", hits);
            out.put("misses", misses);
            out.put("writes", writes);
            out.put("evictions", evictions);
            out.put("staleDeletes", staleDeletes);
            return out;
        }
    }

    /** 计算缓存文件路径；调用方需保证 stackId/dimensionKey 来自白名单。 */
    Path pathFor(String stackId, String dimensionKey, int regionX, int regionZ,
                 int scale, boolean shading, long mtime, long size) {
        Path path = root.resolve(safe(stackId))
                .resolve(RENDER_VERSION)
                .resolve(dimensionPath(dimensionKey))
                .resolve(Integer.toString(scale))
                .resolve(shading ? "1" : "0")
                .resolve("r." + regionX + "." + regionZ + "." + mtime + "." + size + ".png")
                .normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("地图缓存路径越界");
        }
        return path;
    }

    private void ensureScanned() {
        if (scanned) {
            // 缓存目录可能被服主手动删除；重新扫描，避免旧字节数影响后续淘汰。
            if (Files.isDirectory(root)) {
                return;
            }
            memory.clear();
            files.clear();
            bytes = 0;
            scanned = false;
        }
        scanned = true;
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> found = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".png"))
                    .forEach(found::add);
        } catch (IOException e) {
            Samsara.LOGGER.debug("[web] 扫描地图缓存失败 {}: {}", root, e.getMessage());
            return;
        }
        found.sort(Comparator.comparingLong(WebMapTileCache::lastModified));
        for (Path path : found) {
            try {
                long length = Files.size(path);
                files.put(path, length);
                bytes += length;
            } catch (IOException ignored) {
                // 单个文件在扫描期间被删除：跳过即可
            }
        }
        evictIfNeeded();
    }

    private void touch(Path path) {
        files.get(path); // 访问顺序刷新
        try {
            Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // 只影响重启后的 LRU 顺序，不影响正确性
        }
    }

    private void deleteStaleSiblings(Path current, int regionX, int regionZ) {
        Path dir = current.getParent();
        String prefix = "r." + regionX + "." + regionZ + ".";
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> stale = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".png")
                                && !path.equals(current);
                    })
                    .toList();
            for (Path path : stale) {
                try {
                    Files.deleteIfExists(path);
                    Long removed = files.remove(path);
                    if (removed != null) {
                        bytes -= removed;
                    }
                    staleDeletes += 1;
                } catch (IOException ignored) {
                    // 被占用/已删除：留给下次扫描
                }
            }
        } catch (IOException ignored) {
            // 目录不存在或读取失败：不影响当前 tile
        }
    }

    private void evictIfNeeded() {
        Iterator<Map.Entry<Path, Long>> iterator = files.entrySet().iterator();
        while (bytes > maxBytes && iterator.hasNext()) {
            Map.Entry<Path, Long> eldest = iterator.next();
            iterator.remove();
            bytes -= eldest.getValue();
            evictions += 1;
            try {
                Files.deleteIfExists(eldest.getKey());
            } catch (IOException e) {
                Samsara.LOGGER.debug("[web] 淘汰地图缓存失败 {}: {}", eldest.getKey(), e.getMessage());
            }
        }
        if (bytes < 0) {
            bytes = 0;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String memoryKey(String stackId, String dimensionKey, int regionX, int regionZ,
                                    int scale, boolean shading, long mtime, long size) {
        return RENDER_VERSION + "|" + stackId + "|" + dimensionKey + "|" + regionX + "|" + regionZ + "|" + scale
                + "|" + shading + "|" + mtime + "|" + size;
    }

    private static String dimensionPath(String dimensionKey) {
        String key = dimensionKey == null || dimensionKey.isBlank() ? "unknown" : dimensionKey;
        int colon = key.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : key.substring(0, colon);
        String path = colon < 0 ? key : key.substring(colon + 1);
        StringBuilder out = new StringBuilder(safe(namespace));
        for (String part : path.split("/")) {
            if (!part.isBlank()) {
                out.append('/').append(safe(part));
            }
        }
        return out.toString();
    }

    private static String safe(String value) {
        String text = value == null || value.isBlank() ? "unknown" : value;
        return SAFE.matcher(text).replaceAll("_");
    }
}




