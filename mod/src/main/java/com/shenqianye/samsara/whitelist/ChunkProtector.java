package com.shenqianye.samsara.whitelist;

import net.minecraft.server.level.ServerLevel;

/**
 * 倒流保护集计算 —— 哪些区块不允许被"时间倒流"删除。
 * <p>
 * <b>只取决于圈地（whitelist.json 的 regions）</b>：不在任何圈地区域内的区块，
 * 倒流时一律视为"未保留"，可被删除/重生成。
 * 不再自动保护世界出生圈/周目中心圈/分组出生点圈（用户决策 2026-08-16：
 * 是否删除仅取决于旗帜圈地；旗帜系统未就绪前由 /samsara debug claim 维护 regions）。
 * <p>
 * 判定以<b>区块坐标</b>为单位（设计文档 §6.6：方块坐标自动取整到区块）。
 */
public class ChunkProtector {
    private ChunkProtector() {
    }

    /**
     * 判定区块 (chunkX, chunkZ) 是否受保护（不参与倒流删除）。
     *
     * @param overworld 主世界（保留参数，为后续旗帜圈地扩展留接口）
     */
    public static boolean isProtected(ServerLevel overworld, WhitelistData wl, int chunkX, int chunkZ) {
        int bx = chunkX * 16 + 8;
        int bz = chunkZ * 16 + 8;
        for (WhitelistData.Region r : wl.regions) {
            if (r.contains(bx, bz)) {
                return true;
            }
        }
        return false;
    }
}
