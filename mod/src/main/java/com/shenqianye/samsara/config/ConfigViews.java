package com.shenqianye.samsara.config;

import com.google.gson.annotations.SerializedName;
import com.shenqianye.samsara.config.spec.PageSpec;

import java.util.List;
import java.util.Map;

/**
 * 配置 Web API 的视图（record 即 JSON 契约）。
 * <p>
 * 配置值本身是 YAML/JSON 动态值（{@code Object}），但文件、条目、页面、保存结果的结构固定。
 * 可缺省字段用包装类型，null 会被 Gson 省略。
 */
public final class ConfigViews {

    private ConfigViews() {
    }

    /** 一条配置项。{@code default} 是 Java 关键字，用 {@link SerializedName} 保持原字段名。 */
    public record Entry(String path, String type, String description, String group, boolean restart,
                        String visibleWhen, Double min, Double max, List<String> choices,
                        @SerializedName("default") Object defaultValue, Object value,
                        Boolean sensitive, Boolean configured) {
    }

    /** 一个配置文件。 */
    public record File(String id, String name, String path, List<Entry> entries) {
    }

    /** `GET /api/config`：扁平配置模型。 */
    public record Model(List<File> files, List<String> warnings) {
    }

    /** `GET /api/config/spec`：配置编辑器页面。 */
    public record Pages(boolean ok, List<PageSpec> pages, Map<String, String> modNames,
                        List<String> warnings) {
    }

    /** 预览中的单条变更。 */
    public record Change(String path, Object before, Object after) {
    }

    /** 预览/保存结果；不同分支只出现各自字段。 */
    public record Save(boolean ok, String error, String message, Boolean requiresConfirmation,
                       Map<String, String> fields, String file, String revision, Integer changed,
                       List<Change> changes, String diff, PageSpec page, String backupPath) {
    }

    /** `POST /api/reload`：重载配置后的完整模型。 */
    public record Reload(boolean ok, String message, Model config) {
    }

    /** 轨迹保留曲线的一档。 */
    public record CurveTier(int ageDays, double keepRatio, long bytesPerDay, long cumulativeBytes,
                            boolean older) {
    }

    /** `GET /api/config/curve`：轨迹容量估算。 */
    public record Curve(boolean ok, long bytesPerSample, double actualBytesPerSample,
                        long actualTotalBytes, long actualTotalSamples, long actualFileCount,
                        long actualPlayerCount, double actualBytesPerPlayer,
                        long freshSamplesPerDay, long freshBytesPerDay, long steadyBytes,
                        long growthPerDay, boolean unbounded, boolean keepAll, String warning,
                        List<CurveTier> tiers) {
    }
}
