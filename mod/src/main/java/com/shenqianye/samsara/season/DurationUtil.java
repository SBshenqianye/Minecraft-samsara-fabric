package com.shenqianye.samsara.season;

/**
 * 时长解析/格式化工具（支持 d/h/m/s 单位 + 纯数字=秒）。
 */
public final class DurationUtil {
    private DurationUtil() {
    }

    /**
     * 解析时长文本到毫秒。
     * 支持：纯数字（秒）、带单位后缀：s/m/h/d（大小写不限），单位可与数字相邻或隔空格，
     * 也可组合（如 1h30m、1.5h）。
     * 留空或非法返回 0。
     */
    public static long parse(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        String t = s.trim().toLowerCase();
        long total = 0;
        int i = 0;
        // 循环解析「数字+单位」，允许衔接或空格
        while (i < t.length()) {
            if (Character.isWhitespace(t.charAt(i))) {
                i++;
                continue;
            }
            // 取数字开头
            int numStart = i;
            while (i < t.length() && (Character.isDigit(t.charAt(i)) || t.charAt(i) == '.')) {
                i++;
            }
            if (numStart == i) {
                return 0; // 非法字符开头
            }
            double num;
            try {
                num = Double.parseDouble(t.substring(numStart, i));
            } catch (NumberFormatException e) {
                return 0;
            }
            // 取单位（可选字母）
            char unit = 's';
            if (i < t.length() && Character.isLetter(t.charAt(i))) {
                unit = t.charAt(i);
                i++;
            }
            total += Math.round(num * unitMs(unit));
        }
        return total;
    }

    private static double unitMs(char unit) {
        return switch (unit) {
            case 'd' -> 86_400_000L;
            case 'h' -> 3_600_000L;
            case 'm' -> 60_000L;
            case 's' -> 1_000L;
            default -> 1_000L; // 无单位按秒
        };
    }

    /** 把毫秒格式化成可读时长（天/时/分/秒，自适应） */
    public static String formatMs(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long s = Math.round(ms / 1000.0);
        long d = s / 86_400;
        long h = (s % 86_400) / 3_600;
        long m = (s % 3_600) / 60;
        long sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append("d");
        }
        if (h > 0) {
            sb.append(h).append("h");
        }
        if (m > 0) {
            sb.append(m).append("m");
        }
        if (sec > 0 || sb.isEmpty()) {
            sb.append(sec).append("s");
        }
        return sb.toString();
    }
}
