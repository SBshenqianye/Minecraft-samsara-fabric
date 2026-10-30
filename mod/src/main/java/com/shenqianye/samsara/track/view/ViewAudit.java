package com.shenqianye.samsara.track.view;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.shenqianye.samsara.Samsara;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 回看审计（设计 5.7.7 / F-90）：只追加元数据。
 * <p>
 * 文件：{@code config/samsara/audit/view-YYYY-MM-DD.jsonl}。字段仅时间、观看者、
 * 目标、周目、窗口、倍速、结果；<b>不含坐标，不含轨迹内容</b>。
 * 这是 F-81"不写盘"边界内唯一允许的写入（审计元数据）。
 */
public final class ViewAudit {
    private static final Gson GSON = new GsonBuilder().create();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ViewAudit() {
    }

    /**
     * 追加一条元数据。
     *
     * @param result 结果文本（成功/拒绝原因/结束原因）
     */
    public static synchronized void append(String viewer, String target, int cycleId,
                                           long fromMs, long toMs, double speed, String result) {
        JsonObject o = new JsonObject();
        o.addProperty("time", ViewTime.format(System.currentTimeMillis()));
        o.addProperty("localTime", LocalTime.now().format(TIME));
        o.addProperty("viewer", viewer);
        o.addProperty("target", target);
        o.addProperty("cycle", cycleId);
        o.addProperty("from", fromMs);
        o.addProperty("to", toMs);
        o.addProperty("fromText", ViewTime.format(fromMs));
        o.addProperty("toText", ViewTime.format(toMs));
        o.addProperty("speed", speed);
        o.addProperty("result", result);
        Path file = com.shenqianye.samsara.storage.StoragePaths.get()
                .world("audit")
                .resolve("view-" + LocalDate.now() + ".jsonl");
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write(GSON.toJson(o));
                out.newLine();
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[回看] 审计写入失败（不影响会话）: {}", e.getMessage());
        }
    }
}
