package com.shenqianye.samsara.whitelist;

import com.shenqianye.samsara.season.Season;
import com.shenqianye.samsara.season.SeasonData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 倒流保护集计算（M2 档2 用）—— 哪些区块不允许被"时间倒流"删除。
 * <p>
 * 保护集 = 世界出生点保护圈（whitelist.json）∪ 各周目出生中心圈（radius=center.radius）
 *          ∪ 各分组出生点圈（radius=groupMinDistance）∪ whitelist 配置区域。
 * <p>
 * 判定以<b>区块坐标</b>为单位（设计文档 §6.6：方块坐标自动取整到区块）。
 */
public class ChunkProtector {
    private ChunkProtector() {
    }

    /**
     * 判定区块 (chunkX, chunkZ) 是否受保护（不参与倒流删除）。
     *
     * @param overworld 主世界（已获取，避免逐区块 getLevel）
     */
    public static boolean isProtected(ServerLevel overworld, WhitelistData wl, SeasonData sd,
                                      int chunkX, int chunkZ) {
        // 1) 世界出生点保护圈
        BlockPos spawn = overworld.getRespawnData().pos();
        int pr = wl.spawnProtectionRadius;
        if (distChunks(chunkX, chunkZ, spawn.getX() >> 4, spawn.getZ() >> 4) * 16 <= pr) {
            return true;
        }
        // 2) 各周目出生中心圈
        for (Season s : sd.seasons) {
            int r = Math.max(0, s.spawnCenter.radius);
            if (distChunks(chunkX, chunkZ, s.spawnCenter.x >> 4, s.spawnCenter.z >> 4) * 16 <= r) {
                return true;
            }
        }
        // 3) 各分组出生点圈（半径 = 配置 groupMinDistance，默认 300）
        int groupR = Math.max(0, sd.settings.groupMinDistance);
        for (Season s : sd.seasons) {
            for (Season.GroupSpawn g : s.groupSpawns) {
                if (distChunks(chunkX, chunkZ, g.x >> 4, g.z >> 4) * 16 <= groupR) {
                    return true;
                }
            }
        }
        // 4) whitelist.json 配置区域（方块坐标判定；以区块中心代表区块）
        int bx = chunkX * 16 + 8;
        int bz = chunkZ * 16 + 8;
        for (WhitelistData.Region r : wl.regions) {
            if (r.contains(bx, bz)) {
                return true;
            }
        }
        return false;
    }

    private static double distChunks(int cx1, int cz1, int cx2, int cz2) {
        return Math.hypot(cx1 - cx2, cz1 - cz2);
    }
}