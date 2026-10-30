package com.shenqianye.samsara.web;

import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;
import com.shenqianye.samsara.config.YamlStore;
import com.shenqianye.samsara.storage.StoragePaths;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 生产环境的 Web 配置服务：只读写 YAML 配置，不碰世界数据。 */
public final class WebConfigService implements ConfigService {
    @Override
    public Map<String, Object> configModel() {
        return ConfigManager.getInstance().apiModel();
    }

    @Override
    public SaveOutcome save(String fileId, Map<String, Object> values) {
        if (!ConfigSchema.hasFile(fileId)) {
            return new SaveOutcome(false, Map.of("file", "未知配置文件：" + fileId));
        }
        YamlStore.SaveResult result = ConfigManager.getInstance().save(fileId, values);
        return new SaveOutcome(result.ok(), result.errors());
    }

    @Override
    public Map<String, Object> reload() {
        String message = ConfigManager.getInstance().reloadAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", message);
        out.put("config", ConfigManager.getInstance().apiModel());
        return out;
    }

    @Override
    public Map<String, Object> status() {
        StoragePaths paths = StoragePaths.ready() ? StoragePaths.get() : null;
        ConfigManager config = ConfigManager.getInstance();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", FabricLoader.getInstance().getModContainer("samsara")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
        out.put("minecraft", SharedConstants.getCurrentVersion().name());
        out.put("configRoot", paths == null ? StoragePaths.configRoot().toString()
                : paths.configRootPath().toString());
        out.put("worldDataRoot", paths == null ? "（服务器尚未启动）" : paths.worldDataRoot().toString());
        out.put("globalDataRoot", paths == null ? "（服务器尚未启动）" : paths.globalDataRoot().toString());
        out.put("globalFollowWorld", paths == null
                ? config.bool(ConfigSchema.STORAGE, "globalFollowWorld")
                : paths.globalFollowWorld());
        out.put("migration", paths == null ? "尚未初始化" : paths.migrationSummary());
        out.put("globalMigration", paths == null ? "" : paths.globalMigrationSummary());
        List<String> warnings = ConfigManager.getInstance().warnings();
        out.put("warnings", warnings);
        out.put("configFiles", ConfigSchema.fileIds().stream()
                .map(id -> Map.of("id", id, "path",
                        config.file(id).toAbsolutePath().normalize().toString()))
                .toList());
        return out;
    }
}
