package com.shenqianye.samsara.whitelist;

import java.util.ArrayList;
import java.util.List;

/**
 * whitelist.json 根结构（设计文档 §6.2.2 配置白名单）。
 * <p>
 * 当前用途：M2 倒流（档2）判定"哪些区块不允许被倒流删除"。
 * 配置用方块坐标，判定按区块坐标（§6.6：配置中的方块坐标自动取整）。
 */
public class WhitelistData {
    /** 额外区域（circle = 圆心+半径；rect = min/max 方块坐标）。保护集只取决于圈地（regions）。 */
    public List<Region> regions = new ArrayList<>();

    public static class Region {
        public String name = "";
        public String type = "circle";          // circle | rect
        public Integer x;                       // circle: 圆心 x
        public Integer z;                       // circle: 圆心 z
        public Integer radius;                  // circle: 半径（方块）
        public Integer minX;                    // rect: 最小 x
        public Integer minZ;                    // rect: 最小 z
        public Integer maxX;                    // rect: 最大 x
        public Integer maxZ;                    // rect: 最大 z

        /** 是否包含方块坐标 (bx, bz) */
        public boolean contains(int bx, int bz) {
            if ("rect".equalsIgnoreCase(type)) {
                return minX != null && maxX != null && minZ != null && maxZ != null
                        && bx >= minX && bx <= maxX && bz >= minZ && bz <= maxZ;
            }
            // circle
            double r = radius != null ? radius : 0;
            if (x == null || z == null) {
                return false;
            }
            return Math.hypot(bx - x, bz - z) <= r;
        }
    }
}