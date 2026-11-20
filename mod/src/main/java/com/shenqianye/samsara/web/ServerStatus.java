package com.shenqianye.samsara.web;

import java.util.List;

/**
 * {@code GET /api/status} 的类型化响应。
 *
 * <p>字段名与旧 Map 完全一致，前端/脚本无需改动。
 */
public record ServerStatus(
        String version,
        String webVersion,
        String minecraft,
        String configRoot,
        String worldDataRoot,
        String globalDataRoot,
        boolean globalFollowWorld,
        String migration,
        String globalMigration,
        String identityRoot,
        boolean identityEnabled,
        String identityConfig,
        List<String> warnings,
        List<ConfigFileRef> configFiles
) {
    public record ConfigFileRef(String id, String path) {
    }
}
