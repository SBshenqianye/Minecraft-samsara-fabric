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

    /**
     * 规范化顺序与元数据。
     *
     * <p><b>id 与 index 解耦</b>：id 是稳定存储标识（目录名），index 只表示逻辑顺序。
     * 排序只允许改变 index/parent，不允许因为顺序变化把 {@code stack_2} 重命名成
     * {@code stack_0}；否则 Web 排序保存后会被下一次启动重新编号。
     * 只有 id 为空、等于 {@code world} 或与已有 id 冲突时才分配新的 {@code stack_<n>}。
     */
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
                }
            }
        }
        layers.sort(Comparator.comparingInt(l -> l.index));
        if (top != null) {
            layers.remove(top);
            layers.add(top);
        }
        // id 去重：保留已有稳定 id，只给缺失/冲突的层分配新的 stack_<n>。
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (StackLayer l : layers) {
            if (l == top || l.top) {
                continue;
            }
            String id = l.id == null ? "" : l.id.trim();
            if (id.isEmpty() || StackPaths.TOP_ID.equals(id) || used.contains(id)) {
                id = allocateId(used);
                l.id = id;
            } else {
                l.id = id;
            }
            used.add(l.id);
        }
        for (int i = 0; i < layers.size(); i++) {
            StackLayer l = layers.get(i);
            l.index = i;
            l.top = i == layers.size() - 1;
            if (l.top) {
                l.id = StackPaths.TOP_ID;
            }
            l.parent = i == 0 ? "" : layers.get(i - 1).id;
        }
    }

    private static String allocateId(java.util.Set<String> used) {
        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            String candidate = StackPaths.STACK_PREFIX + i;
            if (!used.contains(candidate) && !StackPaths.TOP_ID.equals(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("无法分配栈层 id");
    }

    /**
     * 只调整栈顶以下的逻辑顺序；id/目录保持不变。
     *
     * @param orderedIds 栈底 → 栈顶下方的层 id 列表，必须与现有非栈顶层一一对应
     */
    public void reorderBelowTop(List<String> orderedIds) {
        normalize();
        StackLayer top = top();
        List<StackLayer> old = new ArrayList<>();
        for (StackLayer l : layers) {
            if (!l.top) {
                old.add(l);
            }
        }
        if (orderedIds == null || orderedIds.size() != old.size()) {
            throw new IllegalArgumentException("排序列表必须包含全部非栈顶层");
        }
        List<StackLayer> reordered = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String id : orderedIds) {
            StackLayer found = null;
            for (StackLayer l : old) {
                if (l.id.equals(id)) {
                    found = l;
                    break;
                }
            }
            if (found == null || !seen.add(id)) {
                throw new IllegalArgumentException("排序列表包含未知或重复栈层：" + id);
            }
            reordered.add(found);
        }
        if (reordered.size() != old.size()) {
            throw new IllegalArgumentException("排序列表必须包含全部非栈顶层");
        }
        layers = new ArrayList<>(reordered);
        if (top != null) {
            layers.add(top);
        }
        // 排序语义：只改逻辑 index/parent，不重命名 id。
        for (int i = 0; i < layers.size(); i++) {
            StackLayer l = layers.get(i);
            l.index = i;
            l.top = i == layers.size() - 1;
            l.parent = i == 0 ? "" : layers.get(i - 1).id;
            if (l.top) {
                l.id = StackPaths.TOP_ID;
            }
        }
    }

    public StackLayer top() {
        return layers.isEmpty() ? null : layers.get(layers.size() - 1);
    }

    public List<StackLayer> nonTopLayers() {
        List<StackLayer> out = new ArrayList<>();
        for (StackLayer l : layers) {
            if (l != null && !l.top) {
                out.add(l);
            }
        }
        return out;
    }

    /** 在栈顶以下插入一层；index 为 0=栈底。只改 index/parent，不改其它层 id。 */
    public void insertBelowTop(StackLayer layer, int index) {
        normalize();
        StackLayer top = top();
        List<StackLayer> old = nonTopLayers();
        int at = Math.max(0, Math.min(index, old.size()));
        layer.top = false;
        old.add(at, layer);
        layers = new ArrayList<>(old);
        if (top != null) {
            layers.add(top);
        }
        for (int i = 0; i < layers.size(); i++) {
            StackLayer l = layers.get(i);
            l.index = i;
            l.top = i == layers.size() - 1;
            l.parent = i == 0 ? "" : layers.get(i - 1).id;
            if (l.top) {
                l.id = StackPaths.TOP_ID;
            }
        }
    }

    /** 移除栈顶以下的指定层；不会动目录，调用方负责归档文件。 */
    public StackLayer removeBelowTop(String id) {
        normalize();
        for (int i = 0; i < layers.size(); i++) {
            StackLayer l = layers.get(i);
            if (!l.top && l.id.equals(id)) {
                layers.remove(i);
                normalize();
                return l;
            }
        }
        return null;
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
            java.util.Set<String> used = new java.util.LinkedHashSet<>();
            for (StackLayer l : layers) {
                if (l.id != null) used.add(l.id);
            }
            layer.id = allocateId(used);
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
