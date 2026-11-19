package com.shenqianye.samsara.web;

import com.shenqianye.samsara.storage.StoragePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读「存储布局」模型：每类数据在哪、多大、最近写入、是否随周目/备份。
 *
 * <p>不执行任何写操作，也不返回 token / 文件内容；用于服主确认迁移结果。
 */
public final class StorageLayoutWebService {

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public Map<String, Object> model() {
        long now = System.currentTimeMillis();
        Map<String, Object> hit = cached;
        if (hit != null && now - cachedAt < 30_000L) {
            return hit;
        }
        synchronized (this) {
            if (cached != null && System.currentTimeMillis() - cachedAt < 30_000L) {
                return cached;
            }
            Map<String, Object> built = build();
            cached = built;
            cachedAt = System.currentTimeMillis();
            return built;
        }
    }

    private Map<String, Object> build() {
        StoragePaths paths = StoragePaths.get();
        Path serverRoot = paths.serverRoot();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("config", "服务器配置", paths.configRootPath(),
                false, "进本体备份", "可手改的 YAML / web.yml"));
        rows.add(row("world", "世界数据（当前周目）", paths.worldDataRoot(),
                true, "进本体备份", "圈地、轨迹、地图、容器、当前周目状态"));
        rows.add(row("shared", "跨周目共享数据", paths.sharedDataRoot(),
                false, "进本体备份", "奖池 / 基金 / 保险基金，换周目滚存"));
        rows.add(row("runtime", "运行数据", paths.runtimeDataRoot(),
                false, "进本体备份", "金价缓存 / 开新周目投票 / 公告历史"));
        rows.add(row("identity", "身份组与背包", paths.identityDataRoot(),
                false, "进本体备份", "固定跟随主存档，不随世界栈切换"));
        rows.add(row("global", "全局索引", paths.globalDataRoot(),
                false, "进本体备份", "跟随 globalFollowWorld 的玩家/存档数据"));
        rows.add(row("backups", "备份目录", StoragePaths.backupsRootAt(serverRoot),
                false, "不备份", "本体 / 世界栈 / 删除 / 配置备份"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("serverRoot", serverRoot.toString());
        out.put("globalFollowWorld", paths.globalFollowWorld());
        out.put("rows", rows);
        return out;
    }

    private static Map<String, Object> row(String id, String label, Path path,
                                           boolean followsWorld, String backup, String note) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("label", label);
        row.put("path", path == null ? "" : path.toString());
        row.put("followsWorld", followsWorld);
        row.put("backup", backup);
        row.put("note", note);
        Stats stats = stats(path);
        row.put("bytes", stats.bytes);
        row.put("files", stats.files);
        row.put("updatedAt", stats.updatedAt);
        row.put("exists", stats.exists);
        row.put("truncated", stats.truncated);
        return row;
    }

    private record Stats(long bytes, int files, long updatedAt, boolean exists, boolean truncated) {
    }

    private static Stats stats(Path path) {
        if (path == null || !Files.exists(path)) {
            return new Stats(0, 0, 0, false, false);
        }
        if (Files.isRegularFile(path)) {
            try {
                return new Stats(Files.size(path), 1, Files.getLastModifiedTime(path).toMillis(), true, false);
            } catch (IOException e) {
                return new Stats(0, 1, 0, true, false);
            }
        }
        long[] total = new long[]{0, 0, 0};
        boolean[] truncated = {false};
        // 大世界目录可能几十万文件：给页面 250ms/目录的预算，超时标“≥”，避免接口卡住。
        long deadline = System.currentTimeMillis() + 250;
        int limit = 50_000;
        try (var stream = Files.walk(path)) {
            var iterator = stream.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                try {
                    total[0] += Files.size(file);
                    total[1]++;
                    total[2] = Math.max(total[2], Files.getLastModifiedTime(file).toMillis());
                } catch (IOException ignored) {
                    // 单个文件读不到不影响整体统计（例如正在被写）。
                }
                if (total[1] >= limit || (total[1] % 512 == 0 && System.currentTimeMillis() > deadline)) {
                    truncated[0] = true;
                    break;
                }
            }
        } catch (IOException ignored) {
            // 目录不可读时返回 0，由页面显示“不存在/无法读取”。
        }
        return new Stats(total[0], (int) total[1], total[2], true, truncated[0]);
    }
}
