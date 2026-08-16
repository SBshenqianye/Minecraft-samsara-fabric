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
 * 读取 {@code config/samsara/whitelist.json}；不存在则写入默认值（regions 空）。
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

    /** debug 圈地：添加矩形区域（不保留区 = 未圈地；倒流只保护圈地区域） */
    public String claimAdd(String name, int x1, int z1, int x2, int z2) {
        if (name == null || name.isBlank()) {
            return "圈地名不能为空";
        }
        for (WhitelistData.Region r : data.regions) {
            if (name.equals(r.name)) {
                return "已存在同名圈地「" + name + "」，先 remove 再 add";
            }
        }
        WhitelistData.Region r = new WhitelistData.Region();
        r.name = name;
        r.type = "rect";
        r.minX = Math.min(x1, x2);
        r.minZ = Math.min(z1, z2);
        r.maxX = Math.max(x1, x2);
        r.maxZ = Math.max(z1, z2);
        data.regions.add(r);
        save();
        return "已圈地「" + name + "」(" + r.minX + "," + r.minZ + ")~(" + r.maxX + "," + r.maxZ
                + ") · 这些区块受保护不参与倒流删除（当前共 " + data.regions.size() + " 个圈地）";
    }

    /** debug 圈地：删除 */
    public String claimRemove(String name) {
        for (int i = 0; i < data.regions.size(); i++) {
            if (name.equals(data.regions.get(i).name)) {
                data.regions.remove(i);
                save();
                return "已删除圈地「" + name + "」（剩余 " + data.regions.size() + " 个）";
            }
        }
        return "未找到圈地「" + name + "」（/samsara debug claim list 查看）";
    }

    /** debug 圈地：列表 */
    public String claimList() {
        if (data.regions.isEmpty()) {
            return "当前无圈地 —— 倒流时全部区块都视为未保留（可删除）。\n"
                    + "用 /samsara debug claim add <名> <p1> <p2> 圈地保护（坐标支持 ~ / ~N，方块坐标）\n"
                    + "或用 /samsara debug claim addc <名> <p1> <p2>（区块坐标，自动×16）";
        }
        StringBuilder sb = new StringBuilder("当前圈地（受保护不删）：\n");
        for (WhitelistData.Region r : data.regions) {
            sb.append("  「").append(r.name).append("」 ");
            if ("rect".equalsIgnoreCase(r.type)) {
                sb.append("(").append(r.minX).append(",").append(r.minZ).append(")~(")
                        .append(r.maxX).append(",").append(r.maxZ).append(")");
            } else {
                sb.append("圆心(").append(r.x).append(",").append(r.z).append(") 半径").append(r.radius);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
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