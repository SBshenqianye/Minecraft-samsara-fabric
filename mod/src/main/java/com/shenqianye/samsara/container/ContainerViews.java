package com.shenqianye.samsara.container;

import java.util.List;
import java.util.Map;

/**
 * 容器 Web API 的视图（record 即 JSON 契约）。
 * <p>
 * 物品行仍是 {@code ItemStackModel} 的动态 Map（共享物品模型，单独一片处理）；
 * 容器本身字段固定，全部类型化。可缺省字段用包装类型，null 会被 Gson 省略。
 */
public final class ContainerViews {

    private ContainerViews() {
    }

    /** 单个容器；{@code slots} 只在读取接口出现。 */
    public record Entry(String dimension, int x, int y, int z, String type, int size, int nonEmpty,
                        String axis, boolean empty, long revision, long updatedAt, String source,
                        List<String> slots) {
    }

    /** `/api/containers` 列表。 */
    public record ListPage(boolean ok, String dimension, int radius, String type, String q,
                           int total, List<Entry> containers, int indexSize) implements Outcome {
    }

    /** `/api/containers/read` 成功。 */
    public record Read(boolean ok, Entry container, List<Map<String, Object>> items) implements Outcome {
    }

    /** `/api/containers/scan` 成功。 */
    public record Scan(boolean ok, int queuedChunks, int queuedContainers, int indexSize,
                       String dimension, long cooldownMs) implements Outcome {
    }

    /** 扫描冷却中：{@code retryAfterMs} + 429。 */
    public record ScanBusy(boolean ok, String error, long retryAfterMs, int status) implements Outcome {
        @Override
        public int httpStatus() {
            return status;
        }
    }

    /** 写入结果；{@code status} 决定 HTTP 码，冲突时带 {@code currentRevision}。 */
    public record Write(boolean ok, boolean queued, String message, String error, int status,
                        Long currentRevision, Entry container) implements Outcome {
        @Override
        public int httpStatus() {
            return status;
        }
    }

    /** `/api/containers/offline` 成功。 */
    public record Offline(boolean ok, String uuid, List<String> items) implements Outcome {
    }

    /** 业务错误：{@code {"ok":false,"error":"..."}}。 */
    public record Error(boolean ok, String error) implements Outcome {
    }

    /** 带 HTTP 语义的业务错误：{@code {"ok":false,"status":400,"error":"..."}}。 */
    public record StatusError(boolean ok, int status, String error) implements Outcome {
        @Override
        public int httpStatus() {
            return status;
        }
    }

    /** 容器的统一出口；JSON 只序列化 record 组件。 */
    public sealed interface Outcome permits ListPage, Read, Scan, ScanBusy, Write, Offline, Error,
            StatusError {
        boolean ok();

        default int httpStatus() {
            return ok() ? 200 : 400;
        }
    }
}
