package com.shenqianye.samsara.claim;

import java.util.List;
import java.util.Set;

/**
 * 圈地旗图案判定（纯逻辑，不依赖 Minecraft API）。
 *
 * <p>F-01：必须同时满足
 * <ul>
 *   <li>至少一个 15 种方向图案之一；</li>
 *   <li>至少一个非方向图案（盾徽/图标），避免普通方向横幅被误注册为圈地。</li>
 * </ul>
 */
public final class ClaimPatternRules {
    /** 方向图案只负责指向/延伸；其余图案视为“图标/盾徽”。 */
    public static final Set<String> DIRECTION_PATTERNS =
            Set.copyOf(ClaimRangeConfig.PATTERN_IDS);

    private ClaimPatternRules() {
    }

    public static boolean isDirectionPattern(String id) {
        return id != null && DIRECTION_PATTERNS.contains(id);
    }

    public static boolean isIconPattern(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        // 旗面底图不算图标。
        if ("base".equals(id) || "banner_base".equals(id)) {
            return false;
        }
        return !isDirectionPattern(id);
    }

    public static boolean hasIconPattern(List<String> patternIds) {
        if (patternIds == null) {
            return false;
        }
        for (String id : patternIds) {
            if (isIconPattern(id)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isClaimBanner(List<String> patternIds) {
        if (patternIds == null || patternIds.isEmpty()) {
            return false;
        }
        boolean direction = false;
        boolean icon = false;
        for (String id : patternIds) {
            direction |= isDirectionPattern(id);
            icon |= isIconPattern(id);
            if (direction && icon) {
                return true;
            }
        }
        return false;
    }
}
