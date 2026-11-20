package com.shenqianye.samsara.item;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * 共享物品模型的类型化视图（record 即 JSON 契约）。
 *
 * <p>字段名与旧 {@code Map} 输出逐一对应；数值/布尔用包装类型，保证"空物品只输出
 * {@code empty:true}"这类缺省行为不变（null 会被 Gson 省略）。
 */
public final class ItemStackView {

    private ItemStackView() {
    }

    /** 附魔行。 */
    public record Enchant(String id, String name, int level, int maxLevel) {
    }

    /** 属性修饰符行。 */
    public record Attribute(String id, String name, double amount, String operation, String slot,
                            String modifierId) {
    }

    /** 自定义模型数据。 */
    public record CustomModelData(List<Float> floats, List<Boolean> flags, List<String> strings,
                                  List<Integer> colors) {
    }

    /** 药水效果行。 */
    public record Effect(String id, String name, int duration, int amplifier, boolean ambient,
                         boolean visible) {
    }

    /** 药水内容。 */
    public record Potion(String id, String name, Integer customColor, Integer color,
                         List<Effect> effects) {
    }

    /** 物品编辑器模型（{@code ItemStackModel.describe}）。 */
    public record Detail(Boolean empty, String id, String icon, String name, String zh,
                         Integer count, Integer maxStack, String encoded, String signature,
                         Boolean glint, String components, Integer componentCount, String snbt,
                         String customName, String itemName, List<String> lore,
                         List<Enchant> enchantments, List<Enchant> storedEnchantments,
                         Boolean unbreakable, Integer damage, Integer maxDamage, String rarity,
                         String rarityComponent, Boolean glintOverride, Integer dye, Integer tint,
                         CustomModelData customModelData, List<Attribute> attributes, Potion potion) {
    }

    /** 列表/仓库行模型（{@code ItemStackModel.compact}）。 */
    public record Compact(String id, String icon, String name, String zh, Integer count,
                          Integer maxStack, String encoded, String components, String signature,
                          Boolean glint, Integer tint, Integer componentCount, String customName,
                          List<String> lore, List<Enchant> enchantments, Boolean unbreakable,
                          Integer damage, Integer maxDamage, String rarity,
                          List<Attribute> attributes, Potion potion) {
    }

    /**
     * 扁平化序列化：把 record 里的 {@link Detail}/{@link Compact} 组件展开到同一层，
     * 保持 `{ok:true, ...物品字段}` / `{...物品字段, slot:1}` 的原 JSON 形状。
     */
    public static final class FlatJson implements JsonSerializer<Object> {
        @Override
        public JsonElement serialize(Object src, Type type, JsonSerializationContext context) {
            JsonObject out = new JsonObject();
            try {
                for (RecordComponent component : src.getClass().getRecordComponents()) {
                    Object value = component.getAccessor().invoke(src);
                    if (value == null) {
                        continue;
                    }
                    JsonElement json = context.serialize(value);
                    if (json == null || json.isJsonNull()) {
                        continue;
                    }
                    if (value instanceof Detail || value instanceof Compact) {
                        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                            out.add(entry.getKey(), entry.getValue());
                        }
                    } else {
                        out.add(component.getName(), json);
                    }
                }
            } catch (ReflectiveOperationException e) {
                throw new JsonParseException("物品模型序列化失败", e);
            }
            return out;
        }
    }
}
