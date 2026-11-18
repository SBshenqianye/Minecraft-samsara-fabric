package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 圈地旗注销的纯逻辑：非玩家移除后不得留下幽灵保护，新旗也不继承旧主人。 */
class ClaimNodeRemovalTest {

    private static final List<ClaimNode.PatternEntry> PATTERNS =
            List.of(new ClaimNode.PatternEntry("border", 0));

    @BeforeAll
    static void bootstrapMinecraft() {
        // ClaimNode 的兼容构造会引用 Level.OVERWORLD，需要先完成 registry 引导。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void removingLastBannerDropsNode() {
        ClaimNode node = new ClaimNode(new ChunkCoord(0, 0), "north", PATTERNS, 64,
                UUID.randomUUID(), "旧主人");
        node.addBanner(2, 64, 2, "north", PATTERNS);

        assertTrue(node.hasBannerAt(8, 64, 8));
        assertTrue(node.hasBannerAt(2, 64, 2));

        assertTrue(node.removeBanner(8, 64, 8), "还有一面旗时应保留节点");
        assertFalse(node.hasBannerAt(8, 64, 8));
        assertFalse(node.removeBanner(2, 64, 2), "最后一面旗移除后节点应注销");
        assertFalse(node.hasBannerAt(2, 64, 2));
    }

    @Test
    void staleRemovalIsIgnored() {
        ClaimNode node = new ClaimNode(new ChunkCoord(0, 0), "north", PATTERNS, 64,
                UUID.randomUUID(), "主人");
        assertFalse(node.hasBannerAt(99, 64, 99), "未登记坐标不能触发注销");
        assertTrue(node.removeBanner(99, 64, 99), "误报坐标不能把节点清空");
        assertTrue(node.hasBannerAt(8, 64, 8));
    }

    @Test
    void newBannerAfterRemovalGetsNewOwner() {
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        ClaimNode oldNode = new ClaimNode(new ChunkCoord(3, 4), "north", PATTERNS, 70,
                oldOwner, "旧主人");
        assertFalse(oldNode.removeBanner(3 * 16 + 8, 70, 4 * 16 + 8));

        ClaimNode newNode = new ClaimNode(new ChunkCoord(3, 4), "north", PATTERNS, 70,
                newOwner, "新主人");
        assertEquals(newOwner, newNode.getOwnerUUID());
        assertFalse(oldOwner.equals(newNode.getOwnerUUID()), "新旗不得继承旧主人");
        assertTrue(newNode.hasBannerAt(3 * 16 + 8, 70, 4 * 16 + 8));
    }
}
