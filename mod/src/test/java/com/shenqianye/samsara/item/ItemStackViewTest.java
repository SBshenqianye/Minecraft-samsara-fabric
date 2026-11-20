package com.shenqianye.samsara.item;

import com.google.gson.Gson;
import com.google.gson.annotations.JsonAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 共享物品模型的 JSON 契约：字段名不变、空物品只输出 empty、扁平行不嵌套。 */
class ItemStackViewTest {

    private static final Gson GSON = new Gson();

    @JsonAdapter(ItemStackView.FlatJson.class)
    record Row(ItemStackView.Compact item, int slot) {
    }

    @JsonAdapter(ItemStackView.FlatJson.class)
    record Envelope(boolean ok, ItemStackView.Detail item) {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Object value) {
        return GSON.fromJson(GSON.toJson(value), Map.class);
    }

    @Test
    void emptyDetailOnlyKeepsEmptyFlag() {
        ItemStackView.Detail empty = new ItemStackView.Detail(true, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        assertEquals(Set.of("empty"), json(empty).keySet());
    }

    @Test
    void emptyCompactSerializesToEmptyObject() {
        ItemStackView.Compact empty = new ItemStackView.Compact(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
        assertEquals(Set.of(), json(empty).keySet());
    }

    @Test
    void flatRowPutsItemFieldsAtTopLevel() {
        ItemStackView.Compact item = new ItemStackView.Compact("minecraft:stone", "stone", "石头",
                "石头", 3, 64, "enc", "{}", "sig", false, null, 0, "", List.of(), List.of(),
                false, 0, 0, "common", List.of(), null);
        Map<String, Object> out = json(new Row(item, 7));
        assertFalse(out.containsKey("item"));
        assertEquals(7.0, ((Number) out.get("slot")).doubleValue());
        assertEquals("minecraft:stone", out.get("id"));
        assertEquals("石头", out.get("name"));
        // 可选字段为 null 时不落键，保持旧 Map 行为
        assertFalse(out.containsKey("tint"));
        assertFalse(out.containsKey("potion"));
    }

    @Test
    void flatEnvelopeKeepsOkFirstAndItemFields() {
        ItemStackView.Detail detail = new ItemStackView.Detail(false, "minecraft:stone", "stone",
                "石头", "石头", 1, 64, "enc", "sig", false, "{}", 0, "", "", "", List.of(),
                List.of(), List.of(), false, 0, 0, "common", "", null, null, null, null, List.of(),
                null);
        Map<String, Object> out = json(new Envelope(true, detail));
        assertFalse(out.containsKey("item"));
        assertEquals(true, out.get("ok"));
        assertEquals("minecraft:stone", out.get("id"));
        assertEquals(Set.of("ok", "empty", "id", "icon", "name", "zh", "count", "maxStack",
                "encoded", "signature", "glint", "components", "componentCount", "snbt",
                "customName", "itemName", "lore", "enchantments", "storedEnchantments",
                "unbreakable", "damage", "maxDamage", "rarity", "rarityComponent", "attributes"),
                out.keySet());
    }
}
