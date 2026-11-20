package com.shenqianye.samsara.web;

import java.util.List;
import java.util.Map;

/** {@code GET /api/audit} 的类型化分页响应；entries 仍是审计条目的动态字段。 */
public record AuditPage(
        boolean ok,
        List<Map<String, Object>> entries,
        int offset,
        int limit,
        int total,
        boolean hasMore
) {
}
