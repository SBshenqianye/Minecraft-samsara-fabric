package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回放马的拉丁古名：确定性、长度与字符集。 */
class AncientNameTest {
    @Test
    void deterministicForSeed() {
        assertEquals(AncientName.generate(42L), AncientName.generate(42L));
    }

    @Test
    void namesAreLatinAsciiAndBounded() {
        for (long seed = 0; seed < 200; seed++) {
            String name = AncientName.generate(seed);
            assertFalse(name.isBlank());
            assertTrue(name.length() <= AncientName.MAX_LENGTH, name);
            assertTrue(name.matches("[A-Z][A-Za-z]+"), name);
            assertTrue(Character.isUpperCase(name.charAt(0)), name);
        }
    }

    @Test
    void differentSeedsProduceVariety() {
        String a = AncientName.generate(1L);
        String b = AncientName.generate(2L);
        String c = AncientName.generate(3L);
        assertFalse(a.equals(b) && b.equals(c), "不应永远生成同一个名字");
    }
}
