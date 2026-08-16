package com.shenqianye.samsara.whitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置白名单（M2 起，设计文档 §6.2.2）。单例（设计规范 §6）。
 * <p>
 * 读取 {@code config/samsara/whitelist.json}；不存在则写入默认值
 * （spawnProtectionRadius=1000，regions 空）。
 * <p>
 * 判定逻辑在 {@link ChunkProtector}（结合世界出生点/各周目出生区做动态保护集）。
 */
public class WhitelistStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static WhitelistStore instance;
    private final Path file;
    private WhitelistData data;

    private WhitelistStore() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("samsara");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 samsara 配置目录", e);
        }
        file = dir.resolve("whitelist.json");
        load();
    }

    public static synchronized WhitelistStore getInstance() {
        if (instance == null) {
            instance = new WhitelistStore();
        }
        return instance;
    }

    private void load() {
        if (Files.exists(file)) {
            try {
                data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), WhitelistData.class);
                if (data == null) {
                    data = new WhitelistData();
                }
            } catch (Exception e) {
                throw new RuntimeException("whitelist.json 解析失败（可删除该文件后重启重建）: " + e.getMessage(), e);
            }
        } else {
            data = new WhitelistData();
            save();
        }
    }

    private void save() {
        try {
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("whitelist.json 写入失败: " + e.getMessage(), e);
        }
    }

    public WhitelistData data() {
        return data;
    }

    public String reload() {
        load();
        return "已从磁盘重新加载 " + file.getFileName();
    }

    public String rawJson() {
        return GSON.toJson(data);
    }

    public String configPath() {
        return file.toAbsolutePath().normalize().toString();
    }
}