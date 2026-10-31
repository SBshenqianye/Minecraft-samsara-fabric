package com.shenqianye.samsara.config;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.storage.StoragePaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置文件总入口（F-121~F-126）。
 *
 * <p>目录由 {@link StoragePaths#configRoot()} 决定；本类只负责 YAML 读写与热重载，
 * 不直接拼 {@code FabricLoader.getConfigDir()}。
 *
 * <p>Web 面板与游戏内 {@code /samsara debug reload} 都调用 {@link #reloadAll()}，
 * 保证“保存后立即生效”只有一条路径。
 */
public final class ConfigManager {
    private static ConfigManager instance;

    private final YamlStore store;
    private final Map<String, Map<String, Object>> cache = new LinkedHashMap<>();
    private final Map<String, Backend> backends = new LinkedHashMap<>();
    private final List<String> lastWarnings = new ArrayList<>();
    private Runnable reloadHook;

    /**
     * 非 {@code configRoot} 配置的后端（保留给未来需要独立文件位置的配置）。
     * 注册后，Web/加载/保存/重载统一委托给它，仍复用 ConfigSchema 的默认值与校验。
     */
    public interface Backend {
        Map<String, Object> load();

        YamlStore.SaveResult save(Map<String, Object> updates);

        Map<String, Object> reload();

        Path file();
    }

    private ConfigManager() {
        this.store = new YamlStore(StoragePaths.configRoot(), StoragePaths.legacyConfigRoot());
        reloadAll(false);
    }

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    /** 测试/迁移后重建（仅内部使用）。 */
    public static synchronized void resetForTests(Path configRoot, Path legacyRoot) {
        instance = new ConfigManager(new YamlStore(configRoot, legacyRoot));
    }

    private ConfigManager(YamlStore store) {
        this.store = store;
        reloadAll(false);
    }

    public Path configRoot() {
        return store.dir();
    }

    public Path file(String id) {
        Backend backend = backends.get(id);
        return backend == null ? store.file(id) : backend.file();
    }

    /** 注册/替换一个全局配置后端（内部使用；幂等，可重复初始化）。 */
    public synchronized void registerBackend(String id, Backend backend) {
        if (backend == null) {
            backends.remove(id);
            return;
        }
        backends.put(id, backend);
        cache.put(id, backend.load());
    }

    public synchronized boolean hasBackend(String id) {
        return backends.containsKey(id);
    }

    // ---- 读取 ----

    public synchronized Map<String, Object> snapshot(String id) {
        Map<String, Object> values = cache.get(id);
        return values == null ? Map.of() : new LinkedHashMap<>(values);
    }

    public synchronized Object value(String id, String path) {
        Map<String, Object> values = cache.get(id);
        return values == null ? null : values.get(path);
    }

    public boolean bool(String id, String path) {
        Object value = value(id, path);
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    public int integer(String id, String path) {
        Object value = value(id, path);
        return value instanceof Number n ? n.intValue() : 0;
    }

    public double decimal(String id, String path) {
        Object value = value(id, path);
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    public String string(String id, String path) {
        Object value = value(id, path);
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    public List<String> stringList(String id, String path) {
        Object value = value(id, path);
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
            return out;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> levelMap(String id, String path) {
        Object value = value(id, path);
        if (value instanceof Map<?, ?> map) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            return out;
        }
        return Map.of();
    }

    // ---- 保存 / 重载 ----

    /**
     * 保存一个文件的字段更新。全部校验通过才落盘；失败返回字段级错误。
     * 修改 {@code storage.globalFollowWorld} 时先做数据迁移，迁移失败不保存。
     */
    public synchronized YamlStore.SaveResult save(String id, Map<String, Object> updates) {
        Backend backend = backends.get(id);
        if (backend != null) {
            YamlStore.SaveResult delegated = backend.save(updates);
            if (delegated.ok()) {
                cache.put(id, backend.load());
                fireReload(id);
            }
            return delegated;
        }
        boolean switchGlobal = false;
        boolean targetFollow = false;
        if (ConfigSchema.STORAGE.equals(id) && updates != null
                && updates.containsKey("globalFollowWorld")) {
            Object raw = updates.get("globalFollowWorld");
            targetFollow = raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw));
            switchGlobal = targetFollow != bool(ConfigSchema.STORAGE, "globalFollowWorld");
            if (switchGlobal) {
                String error = StoragePaths.applyGlobalFollowWorld(targetFollow);
                if (error != null) {
                    return new YamlStore.SaveResult(false,
                            Map.of("globalFollowWorld", "迁移失败，已保持原位置：" + error));
                }
            }
        }
        YamlStore.SaveResult result = store.save(id, updates);
        if (result.ok()) {
            cache.put(id, store.values(id));
            lastWarnings.clear();
            lastWarnings.addAll(store.warnings());
            fireReload(id);
        } else if (switchGlobal) {
            // 配置没写成功：把已迁移的数据搬回去，开关保持旧值。
            StoragePaths.applyGlobalFollowWorld(!targetFollow);
        }
        return result;
    }

    /** 重读全部 YAML，并触发热重载。 */
    public synchronized String reloadAll() {
        return reloadAll(true);
    }

    private synchronized String reloadAll(boolean fireHook) {
        StringBuilder message = new StringBuilder();
        lastWarnings.clear();
        store.clearWarnings();
        for (String id : ConfigSchema.fileIds()) {
            Backend backend = backends.get(id);
            Map<String, Object> values;
            if (backend != null) {
                values = backend.reload();
            } else {
                values = store.load(id);
                for (String warning : store.warnings()) {
                    if (!lastWarnings.contains(warning)) {
                        lastWarnings.add(warning);
                        Samsara.LOGGER.warn("[周目管理 samsara] 配置：{}", warning);
                    }
                }
            }
            cache.put(id, values);
            if (message.length() > 0) {
                message.append(" · ");
            }
            message.append(ConfigSchema.fileName(id)).append(".yml");
        }
        if (fireHook) {
            fireReload(null);
        }
        return "配置已重载：" + message;
    }

    /** 重读单个文件（Web 保存后使用）。 */
    public synchronized Map<String, Object> reload(String id) {
        Backend backend = backends.get(id);
        Map<String, Object> values = backend == null ? store.reload(id) : backend.reload();
        cache.put(id, values);
        fireReload(id);
        return values;
    }

    public List<String> warnings() {
        return List.copyOf(lastWarnings);
    }

    public void setReloadHook(Runnable hook) {
        this.reloadHook = hook;
    }

    private void fireReload(String id) {
        if (reloadHook != null) {
            try {
                reloadHook.run();
            } catch (RuntimeException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 配置热重载失败: {}", e.getMessage());
            }
        }
    }

    /** Web API 使用的 schema + 当前值模型。 */
    public synchronized Map<String, Object> apiModel() {
        List<Map<String, Object>> files = new ArrayList<>();
        for (String id : ConfigSchema.fileIds()) {
            if (ConfigSchema.OUTPUT.equals(id)) {
                // 输出模板有独立变量元数据，走 /api/output，不在扁平配置表单里重复编辑
                continue;
            }
            if (ConfigSchema.PERMISSIONS.equals(id)) {
                // 权限映射与关闭列表由权限树统一维护；配置文件不再在配置页重复出现
                continue;
            }
            Map<String, Object> values = cache.getOrDefault(id, Map.of());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (ConfigEntry entry : ConfigSchema.entries(id)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", entry.path());
                item.put("type", entry.type().id());
                item.put("description", entry.description());
                item.put("group", entry.group());
                item.put("restart", entry.restart());
                item.put("min", entry.min());
                item.put("max", entry.max());
                item.put("choices", entry.choices());
                item.put("default", ConfigSchema.deepCopy(entry.defaultValue()));
                if (ConfigSchema.isSensitive(id, entry.path())) {
                    item.put("value", "");
                    item.put("sensitive", true);
                    item.put("configured", !string(id, entry.path()).isBlank());
                } else {
                    item.put("value", ConfigSchema.deepCopy(values.get(entry.path())));
                }
                entries.add(item);
            }
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("id", id);
            file.put("name", ConfigSchema.fileName(id) + ".yml");
            file.put("path", file(id).toString());
            file.put("entries", entries);
            files.add(file);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("files", files);
        model.put("warnings", warnings());
        return model;
    }
}
