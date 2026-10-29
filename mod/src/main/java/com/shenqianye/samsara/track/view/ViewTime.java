package com.shenqianye.samsara.track.view;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 回看时间解析（设计 5.7.6 / F-88）。
 * <p>
 * 支持：epoch 毫秒、{@code yyyy-MM-dd}、{@code HH:mm}、{@code yyyy-MM-ddTHH:mm}
 * （以及带秒的 ISO 本地时间）。与 {@code TrackReplay.parseTime} 的差别只在
 * {@code --to}：{@code HH:mm} 相对<b>开始时间所在日期</b>，且早于开始时间时顺延一天。
 * <p>
 * 命令层使用 {@code StringArgumentType.string()}，因此 {@code :} 不会被 Brigadier 截断。
 */
public final class ViewTime {
    private ViewTime() {
    }

    /** 绝对时间（yyyy-MM-dd 表示当天 00:00；HH:mm 表示今天）。 */
    public static long parseAbsolute(String spec) {
        String v = require(spec);
        if (v.matches("\\d{10,}")) {
            return Long.parseLong(v);
        }
        ZoneId zone = ZoneId.systemDefault();
        try {
            return LocalDateTime.parse(v).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalTime.parse(v).atDate(LocalDate.now()).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(v).atStartOfDay(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        throw new IllegalArgumentException("时间格式无效（支持 epoch 毫秒、yyyy-MM-dd、HH:mm、yyyy-MM-ddTHH:mm）: " + v);
    }

    /**
     * 解析结束时间。{@code HH:mm} 落在开始日期；若不晚于开始时刻则视为次日。
     * 其他格式按绝对时间；若仍不晚于开始时刻则报错。
     */
    public static long parseEnd(String spec, long startMs) {
        String v = require(spec);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate startDay = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate();
        try {
            LocalDate endDay = LocalDate.parse(v);
            long t = endDay.atTime(23, 59, 59, 999_000_000)
                    .atZone(zone).toInstant().toEpochMilli();
            if (t <= startMs) {
                throw new IllegalArgumentException("结束时间必须晚于开始时间");
            }
            return t;
        } catch (DateTimeParseException ignored) {
        }
        if (!v.matches("\\d{10,}")) {
            try {
                LocalTime time = LocalTime.parse(v);
                long t = time.atDate(startDay).atZone(zone).toInstant().toEpochMilli();
                return t > startMs ? t : t + 86_400_000L;
            } catch (DateTimeParseException ignored) {
            }
        }
        long t = parseAbsolute(v);
        if (t <= startMs) {
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        }
        return t;
    }

    private static String require(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("时间不能为空");
        }
        return spec.trim();
    }

    /** 状态/日志用的本地时间文本 */
    public static String format(long epochMs) {
        if (epochMs == Long.MIN_VALUE) {
            return "-";
        }
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ');
    }

    /** 日期目录名（本地时区） */
    public static LocalDate dayOf(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
