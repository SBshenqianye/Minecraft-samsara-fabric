package com.shenqianye.samsara.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 物品中文名资源：从 assets 读取，不再依赖 /web 静态目录。 */
class ItemLangTest {
    @Test
    void readsChineseNameFromAssets() {
        assertNotNull(ItemLangTest.class.getResource("/assets/samsara/lang/item-lang.json"),
                "classpath missing item-lang.json");
        assertEquals("钻石", ItemLang.zh("minecraft:diamond"));
        assertEquals("", ItemLang.zh(""));
        assertEquals("", ItemLang.zh("minecraft:not_a_real_item"));
    }
}
