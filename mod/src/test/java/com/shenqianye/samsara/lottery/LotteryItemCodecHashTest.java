package com.shenqianye.samsara.lottery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 隔离区日志只写哈希，不泄露物品原文。 */
class LotteryItemCodecHashTest {

    @Test
    void hashIsStableAndShort() {
        String first = LotteryItemCodec.hash("CgMABWNvdW50AAAAAQgAAmlk");
        String second = LotteryItemCodec.hash("CgMABWNvdW50AAAAAQgAAmlk");
        assertEquals(first, second);
        assertEquals(16, first.length());
    }

    @Test
    void hashDoesNotContainEncodedText() {
        String encoded = "very-secret-nbt-payload";
        String hash = LotteryItemCodec.hash(encoded);
        assertFalse(hash.contains("secret"));
        assertTrue(hash.matches("[0-9a-f]{16}"));
    }

    @Test
    void differentItemsGetDifferentHashes() {
        assertNotEquals(LotteryItemCodec.hash("a"), LotteryItemCodec.hash("b"));
        assertEquals("", LotteryItemCodec.hash(null));
    }
}
