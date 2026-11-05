package com.shenqianye.samsara.config.spec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置页描述：页面元数据 + 表单 + 当前值 + 动作。
 *
 * <p>{@code source} 为 samsara / server / third-party；第三方与服务器配置默认要求
 * 预览确认后才写入。复杂页面通过 {@code customWidget} 指回原有专用页面。
 */
public record PageSpec(
        String id,
        String title,
        String fileName,
        String path,
        String format,
        String source,
        String revision,
        boolean writable,
        boolean requiresConfirmation,
        String readOnlyReason,
        String customWidget,
        FormSpec form,
        List<PageAction> actions,
        Map<String, Object> values) {

    public PageSpec {
        id = id == null ? "" : id;
        title = title == null || title.isBlank() ? id : title;
        fileName = fileName == null ? "" : fileName;
        path = path == null ? "" : path;
        format = format == null ? "unknown" : format;
        source = source == null ? "samsara" : source;
        revision = revision == null ? "" : revision;
        readOnlyReason = readOnlyReason == null ? "" : readOnlyReason;
        customWidget = customWidget == null ? "" : customWidget;
        form = form == null ? new FormSpec(id, List.of(), List.of()) : form;
        actions = actions == null ? List.of() : List.copyOf(actions);
        values = values == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
