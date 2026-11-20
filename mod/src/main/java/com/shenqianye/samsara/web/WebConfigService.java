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
        // 公告是独立 JSON 存储（不走 YAML 配置），也要跟着重新读取；
        // 否则手动修复/外部编辑 announcements.json 后只能重启才生效。
        com.shenqianye.samsara.announce.AnnouncementService.getInstance().reload();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", message);
        out.put("config", ConfigManager.getInstance().apiModel());
        return out;
    }

    @Override
    public ServerStatus status() {
        StoragePaths paths = StoragePaths.ready() ? StoragePaths.get() : null;
        ConfigManager config = ConfigManager.getInstance();
        String version = FabricLoader.getInstance().getModContainer("samsara")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        String configRoot = paths == null ? StoragePaths.configRoot().toString()
                : paths.configRootPath().toString();
        String worldRoot = paths == null ? "（服务器尚未启动）" : paths.worldDataRoot().toString();
        String globalRoot = paths == null ? "（服务器尚未启动）" : paths.globalDataRoot().toString();
        boolean follow = paths == null
                ? config.bool(ConfigSchema.STORAGE, "globalFollowWorld")
                : paths.globalFollowWorld();
        com.shenqianye.samsara.identity.IdentityService identity =
                com.shenqianye.samsara.identity.IdentityService.getInstance();
        String identityRoot = identity.identityRoot() == null ? "（服务器尚未启动）"
                : identity.identityRoot().toString();
        String identityConfig = identity.configStore() == null ? ""
                : identity.configStore().file().toString();
        List<ServerStatus.ConfigFileRef> files = ConfigSchema.fileIds().stream()
                .map(id -> new ServerStatus.ConfigFileRef(id,
                        config.file(id).toAbsolutePath().normalize().toString()))
                .toList();
        return new ServerStatus(version, WebVersion.VALUE,
                SharedConstants.getCurrentVersion().name(), configRoot, worldRoot, globalRoot, follow,
                paths == null ? "尚未初始化" : paths.migrationSummary(),
                paths == null ? "" : paths.globalMigrationSummary(),
                identityRoot, identity.enabled(), identityConfig,
                ConfigManager.getInstance().warnings(), files);
    }
}
