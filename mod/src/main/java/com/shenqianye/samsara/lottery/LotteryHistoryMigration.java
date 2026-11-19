package com.shenqianye.samsara.lottery;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.season.StackPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 旧世界乐透历史合并：只把旧栈层里「已结算/已取消」的轮次复制到跨周目 shared。
 *
 * <p>不合并 fund / state / claims——那会把旧世界的奖池金额、当前轮次和待领取重复计一遍。
 * 目标已存在时跳过，源文件永远保留。
 */
public final class LotteryHistoryMigration {

    private static final Gson GSON = new Gson();

    private LotteryHistoryMigration() {
    }

    public record Result(int imported, int skipped, int failed, List<String> notes) {
        public String summary() {
            String base = "旧世界乐透历史：导入 " + imported + "，跳过 " + skipped + "，失败 " + failed;
            return notes.isEmpty() ? base : base + "（" + String.join("；", notes) + "）";
        }
    }

    /**
     * @param worldRoot         主存档根（含 {@code samsara} 与世界栈层）
     * @param sharedLotteryRoot 跨周目数据根下的 {@code lottery}
     */
    public static Result merge(Path worldRoot, Path sharedLotteryRoot) {
        List<String> notes = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        if (worldRoot == null || sharedLotteryRoot == null) {
            return new Result(0, 0, 0, List.of("路径未初始化"));
        }
        Path targetDays = sharedLotteryRoot.resolve("days");
        for (Path root : StackPaths.dataRoots(worldRoot)) {
            Path oldDays = root.resolve("lottery").resolve("days");
            if (!Files.isDirectory(oldDays)) {
                continue;
            }
            try (var stream = Files.list(oldDays)) {
                for (Path file : (Iterable<Path>) stream.sorted()::iterator) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".json") || !Files.isRegularFile(file)) {
                        continue;
                    }
                    String state;
                    try {
                        JsonObject json = JsonParser.parseString(
                                Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                        state = json.has("state") ? json.get("state").getAsString() : "";
                    } catch (RuntimeException e) {
                        failed++;
                        notes.add(name + " 解析失败");
                        continue;
                    }
                    if (!"SETTLED".equals(state) && !"CANCELLED".equals(state)) {
                        skipped++;
                        continue;
                    }
                    Path target = targetDays.resolve(name);
                    if (Files.exists(target)) {
                        skipped++;
                        continue;
                    }
                    try {
                        Files.createDirectories(targetDays);
                        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                        if (Files.size(file) != Files.size(target)) {
                            Files.deleteIfExists(target);
                            failed++;
                            notes.add(name + " 复制后大小不一致");
                        } else {
                            imported++;
                        }
                    } catch (IOException e) {
                        failed++;
                        notes.add(name + " 复制失败：" + e.getMessage());
                    }
                }
            } catch (IOException e) {
                failed++;
                notes.add(oldDays + " 不可读：" + e.getMessage());
            }
        }
        Result result = new Result(imported, skipped, failed, notes);
        if (imported > 0 || failed > 0) {
            Samsara.LOGGER.info("[lottery] {}", result.summary());
        }
        return result;
    }

    /** 读取旧轮次文件用于测试/诊断（不修改）。 */
    static JsonObject read(Path file) throws IOException {
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
    }
}
