package com.shenqianye.samsara.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 栈清单：{@code world/samsara/stack.json}。
 *
 * <p>顺序：index 0 = 栈底/最旧，最后一个 = 栈顶 {@code world}。数字越大越新。
 */
public final class StackManifest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int CURRENT_VERSION = 2;

    public int schemaVersion = CURRENT_VERSION;
    /** index 升序，栈顶在最后。 */
    public List<StackLayer> layers = new ArrayList<>();

    public static StackManifest load(Path worldDir) {
        StackManifest m = new StackManifest();
        Path file = StackPaths.manifest(worldDir);
        if (Files.exists(file)) {
            try {
                StackManifest loaded = GSON.fromJson(
                        Files.readString(file, StandardCharsets.UTF_8), StackManifest.class);
                if (loaded != null) {
                    m = loaded;
                }
            } catch (Exception e) {
                Samsara.LOGGER.warn("[周目管理 samsara] {} 解析失败，尝试扫描栈层: {}",
                        file, e.getMessage());
            }
        }
        if (m.layers == null) {
            m.layers = new ArrayList<>();
        }
        // 扫描 {samsara}/stack/*/stack.json，补齐清单缺失的层
        Path stackRoot = StackPaths.stackMetaRoot(worldDir);
        if (Files.isDirectory(stackRoot)) {
            try (var stream = Files.list(stackRoot)) {
                for (Path dir : (Iterable<Path>) stream.sorted()::iterator) {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    Path meta = dir.resolve("stack.json");
                    if (!Files.exists(meta)) {
                        continue;
                    }
                    try {
                        StackLayer layer = GSON.fromJson(
                                Files.readString(meta, StandardCharsets.UTF_8), StackLayer.class);
                        if (layer != null && !layer.top && m.byId(layer.id) == null) {
                            layer.normalize();
                            m.layers.add(layer);
                        }
                    } catch (Exception e) {
                        Samsara.LOGGER.warn("[周目管理 samsara] {} 解析失败: {}", meta, e.getMessage());
                    }
                }
            } catch (IOException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 扫描栈层失败: {}", e.getMessage());
            }
        }
        m.normalize();
        return m;
    }

    public void normalize() {
        if (layers == null) {
            layers = new ArrayList<>();
        }
        layers.removeIf(l -> l == null);
        for (StackLayer l : layers) {
            l.normalize();
        }
        // 栈顶必须唯一且在最后
        StackLayer top = null;
        for (StackLayer l : layers) {
            if (l.top) {
                if (top == null) {
                    top = l;
                } else {
                    l.top = false;
                    if (l.id == null || l.id.isBlank() || StackPaths.TOP_ID.equals(l.id)) {
                        l.id = StackPaths.STACK_PREFIX + l.index;
                    }
                }
            }
        }
        layers.sort(Comparator.comparingInt(l -> l.index));
        if (top != null) {
            layers.remove(top);
            layers.add(top);
        }
        for (int i = 0; i < layers.size(); i++) {
            StackLayer l = layers.get(i);
            l.index = i;
            l.top = i == layers.size() - 1;
            if (l.top) {
                l.id = StackPaths.TOP_ID;
                l.parent = i == 0 ? "" : layers.get(i - 1).id;
            } else {
                if (l.id == null || l.id.isBlank() || StackPaths.TOP_ID.equals(l.id)) {
                    l.id = StackPaths.STACK_PREFIX + i;
                }
                l.parent = i == 0 ? "" : layers.get(i - 1).id;
            }
        }
    }

    public StackLayer top() {
        return layers.isEmpty() ? null : layers.get(layers.size() - 1);
    }

    public StackLayer byId(String id) {
        if (id == null) {
            return null;
        }
        for (StackLayer l : layers) {
            if (id.equals(l.id)) {
                return l;
            }
        }
        return null;
    }

    public StackLayer byIndex(int index) {
        for (StackLayer l : layers) {
            if (l.index == index) {
                return l;
            }
        }
        return null;
    }

    public StackLayer below(StackLayer layer) {
        int i = layers.indexOf(layer);
        return i > 0 ? layers.get(i - 1) : null;
    }

    public StackLayer above(StackLayer layer) {
        int i = layers.indexOf(layer);
        return i >= 0 && i + 1 < layers.size() ? layers.get(i + 1) : null;
    }

    public void ensureTop(StackLayer top) {
        layers.removeIf(l -> l.top || StackPaths.TOP_ID.equals(l.id));
        top.top = true;
        top.id = StackPaths.TOP_ID;
        layers.add(top);
        normalize();
    }

    public StackLayer addOld(StackLayer layer) {
        layer.top = false;
        if (layer.id == null || layer.id.isBlank() || StackPaths.TOP_ID.equals(layer.id)) {
            layer.id = StackPaths.STACK_PREFIX + (layers.size());
        }
        layers.add(layer);
        normalize();
        return layer;
    }

    public void save(Path worldDir) {
        normalize();
        Path file = StackPaths.manifest(worldDir);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] stack.json 写入失败: {}", e.getMessage());
        }
        // 每层自己的 stack.json（栈顶就是清单本身）
        for (StackLayer layer : layers) {
            if (layer.top) {
                continue;
            }
            Path meta = StackPaths.layerStackFile(worldDir, layer);
            try {
                Files.createDirectories(meta.getParent());
                Files.writeString(meta, GSON.toJson(layer), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] {} 写入失败: {}", meta, e.getMessage());
            }
        }
    }

    public String summary() {
        List<String> parts = new ArrayList<>();
        for (StackLayer l : layers) {
            parts.add(l.index + "=" + l.id);
        }
        return String.join(" → ", parts);
    }
}
