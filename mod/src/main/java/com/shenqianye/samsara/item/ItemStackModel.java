package com.shenqianye.samsara.item;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.util.Unit;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 物品结构化模型：把 ItemStack 的常用组件翻译成 Web 表单可编辑的字段，并支持反向写回。
 *
 * <p>只处理管理员会手动编辑的组件；未知组件在编辑时保持原样，避免把模组物品写坏。
 */
public final class ItemStackModel {
    private ItemStackModel() {
    }

    // ---- 读取 ----

    public static Map<String, Object> describe(ItemStack stack, RegistryAccess registries, String encoded) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (stack == null || stack.isEmpty()) {
            out.put("empty", true);
            return out;
        }
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        int colon = id.indexOf(':');
        out.put("empty", false);
        out.put("id", id);
        out.put("icon", colon >= 0 ? id.substring(colon + 1) : id);
        out.put("name", stack.getHoverName().getString());
        out.put("zh", ItemLang.zh(id));
        out.put("count", stack.getCount());
        out.put("maxStack", stack.getMaxStackSize());
        out.put("encoded", encoded == null ? "" : encoded);
        out.put("signature", signature(stack));
        out.put("glint", stack.hasFoil());
        out.put("components", stack.getComponents().toString());
        out.put("componentCount", stack.getComponents().size());
        out.put("snbt", snbt(stack, registries));

        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        out.put("customName", customName == null ? "" : customName.getString());
        Component itemName = stack.get(DataComponents.ITEM_NAME);
        out.put("itemName", itemName == null ? "" : itemName.getString());
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> loreLines = new ArrayList<>();
        if (lore != null) {
            lore.lines().forEach(line -> loreLines.add(line.getString()));
        }
        out.put("lore", loreLines);
        out.put("enchantments", enchantments(stack.get(DataComponents.ENCHANTMENTS)));
        out.put("storedEnchantments", enchantments(stack.get(DataComponents.STORED_ENCHANTMENTS)));
        out.put("unbreakable", stack.has(DataComponents.UNBREAKABLE));
        out.put("damage", stack.getDamageValue());
        out.put("maxDamage", stack.getMaxDamage());
        // 与原版 ItemStack#getRarity 一致：附魔会把普通/罕见抬到稀有，稀有抬到史诗。
        out.put("rarity", stack.getRarity().getSerializedName());
        // 编辑器只认“显式覆盖”的组件，避免把物品默认值或附魔推导值写死进物品。
        Rarity explicitRarity = stack.get(DataComponents.RARITY);
        Rarity defaultRarity = stack.getPrototype() == null
                ? null : stack.getPrototype().get(DataComponents.RARITY);
        out.put("rarityComponent", explicitRarity == null || explicitRarity == defaultRarity
                ? "" : explicitRarity.getSerializedName());
        out.put("glintOverride", stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
        out.put("dye", dye(stack));
        out.put("tint", tint(stack));
        out.put("customModelData", customModelData(stack.get(DataComponents.CUSTOM_MODEL_DATA)));
        out.put("attributes", attributes(stack.get(DataComponents.ATTRIBUTE_MODIFIERS)));
        out.put("potion", potion(stack.get(DataComponents.POTION_CONTENTS)));
        return out;
    }

    /** 容器/背包列表用的轻量模型。 */
    public static Map<String, Object> compact(ItemStack stack, String encoded) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (stack == null || stack.isEmpty()) return out;
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        int colon = id.indexOf(':');
        out.put("id", id);
        out.put("icon", colon >= 0 ? id.substring(colon + 1) : id);
        out.put("name", stack.getHoverName().getString());
        out.put("zh", ItemLang.zh(id));
        out.put("count", stack.getCount());
        out.put("maxStack", stack.getMaxStackSize());
        out.put("encoded", encoded == null ? "" : encoded);
        out.put("components", stack.getComponents().toString());
        out.put("signature", signature(stack));
        out.put("glint", stack.hasFoil());
        Integer tint = tint(stack);
        out.put("tint", tint);
        out.put("componentCount", stack.getComponents().size());
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        out.put("customName", customName == null ? "" : customName.getString());
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> loreLines = new ArrayList<>();
        if (lore != null) lore.lines().forEach(line -> loreLines.add(line.getString()));
        out.put("lore", loreLines);
        out.put("enchantments", enchantments(stack.get(DataComponents.ENCHANTMENTS)));
        out.put("unbreakable", stack.has(DataComponents.UNBREAKABLE));
        out.put("damage", stack.getDamageValue());
        out.put("maxDamage", stack.getMaxDamage());
        out.put("rarity", stack.getRarity().getSerializedName());
        out.put("attributes", attributes(stack.get(DataComponents.ATTRIBUTE_MODIFIERS)));
        out.put("potion", potion(stack.get(DataComponents.POTION_CONTENTS)));
        return out;
    }

    /** 完整物品 SNBT（含 id/count/components），供 Web 原始 NBT 编辑。 */
    public static String snbt(ItemStack stack, RegistryAccess registries) {
        if (stack == null || stack.isEmpty() || registries == null) return "";
        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
            return ItemStack.CODEC.encodeStart(ops, stack).getOrThrow().toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 解析 Web 提交的完整物品 SNBT。 */
    public static ItemStack parseSnbt(String text, RegistryAccess registries) {
        if (text == null || text.isBlank() || registries == null) return ItemStack.EMPTY;
        try {
            CompoundTag tag = TagParser.parseCompoundFully(text.trim());
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
            return ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
        } catch (CommandSyntaxException | RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    /** 物品身份（不含数量）；用于拖拽合并时判断是不是同一种物品。 */
    public static String signature(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        return id + "|" + stack.getComponents().toString();
    }

    private static List<Map<String, Object>> enchantments(ItemEnchantments value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value == null || value.isEmpty()) return out;
        for (Map.Entry<Holder<Enchantment>, Integer> entry : value.entrySet()) {
            String id = key(entry.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", entry.getKey().value().description().getString());
            row.put("level", entry.getValue());
            row.put("maxLevel", entry.getKey().value().getMaxLevel());
            out.add(row);
        }
        out.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
        return out;
    }

    private static List<Map<String, Object>> attributes(ItemAttributeModifiers value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value == null) return out;
        value.modifiers().forEach(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            String id = key(entry.attribute());
            row.put("id", id);
            row.put("name", Component.translatable(entry.attribute().value().getDescriptionId()).getString());
            row.put("amount", entry.modifier().amount());
            row.put("operation", entry.modifier().operation().getSerializedName());
            row.put("slot", entry.slot().getSerializedName());
            row.put("modifierId", entry.modifier().id().toString());
            out.add(row);
        });
        return out;
    }

    private static Integer dye(ItemStack stack) {
        DyedItemColor color = stack.get(DataComponents.DYED_COLOR);
        return color == null ? null : color.rgb();
    }

    private static Integer tint(ItemStack stack) {
        DyedItemColor color = stack.get(DataComponents.DYED_COLOR);
        if (color != null) return color.rgb();
        PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
        if (potion != null && potion.hasEffects()) return potion.getColor();
        return null;
    }

    private static Map<String, Object> customModelData(CustomModelData value) {
        if (value == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("floats", new ArrayList<>(value.floats()));
        out.put("flags", new ArrayList<>(value.flags()));
        out.put("strings", new ArrayList<>(value.strings()));
        out.put("colors", new ArrayList<>(value.colors()));
        return out;
    }

    private static Map<String, Object> potion(PotionContents value) {
        if (value == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        String id = value.potion().flatMap(Holder::unwrapKey)
                .map(ItemStackModel::key).orElse("");
        out.put("id", id);
        out.put("name", id.isBlank() ? "" : pretty(id));
        out.put("customColor", value.customColor().orElse(null));
        out.put("color", value.hasEffects() ? Integer.valueOf(value.getColor()) : null);
        List<Map<String, Object>> effects = new ArrayList<>();
        for (MobEffectInstance effect : value.customEffects()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", key(effect.getEffect()));
            row.put("name", effect.getEffect().value().getDisplayName().getString());
            row.put("duration", effect.getDuration());
            row.put("amplifier", effect.getAmplifier());
            row.put("ambient", effect.isAmbient());
            row.put("visible", effect.isVisible());
            effects.add(row);
        }
        out.put("effects", effects);
        return out;
    }

    // ---- 写入 ----

    /** 按表单字段重写可编辑组件；未给出的字段按空处理。 */
    @SuppressWarnings("unchecked")
    public static List<String> apply(ItemStack stack, RegistryAccess registries, Map<String, Object> patch) {
        List<String> errors = new ArrayList<>();
        if (stack == null || stack.isEmpty()) return List.of("物品不存在");
        stack.remove(DataComponents.CUSTOM_NAME);
        stack.remove(DataComponents.LORE);
        stack.remove(DataComponents.ENCHANTMENTS);
        stack.remove(DataComponents.STORED_ENCHANTMENTS);
        stack.remove(DataComponents.UNBREAKABLE);
        stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        stack.remove(DataComponents.RARITY);
        stack.remove(DataComponents.DYED_COLOR);
        stack.remove(DataComponents.CUSTOM_MODEL_DATA);
        stack.remove(DataComponents.POTION_CONTENTS);
        stack.remove(DataComponents.ATTRIBUTE_MODIFIERS);
        stack.remove(DataComponents.DAMAGE);

        String name = str(patch.get("customName"));
        if (!name.isBlank()) stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));

        List<String> lore = strings(patch.get("lore"));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                if (!line.isBlank()) lines.add(Component.literal(line));
            }
            if (!lines.isEmpty()) stack.set(DataComponents.LORE, new ItemLore(lines));
        }

        Integer maxDamage = intOrNull(patch.get("maxDamage"));
        if (maxDamage != null && maxDamage > 0) {
            stack.set(DataComponents.MAX_DAMAGE, maxDamage);
        }
        Integer damage = intOrNull(patch.get("damage"));
        if (damage != null && damage > 0) {
            int limit = Math.max(0, stack.getMaxDamage() - 1);
            stack.set(DataComponents.DAMAGE, Math.max(0, Math.min(damage, limit)));
        }

        Object glint = patch.get("glintOverride");
        if (glint instanceof Boolean value) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, value);
        }

        if (Boolean.TRUE.equals(patch.get("unbreakable"))) {
            stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        }

        String rarity = str(patch.get("rarity"));
        if (!rarity.isBlank()) {
            for (Rarity value : Rarity.values()) {
                if (value.getSerializedName().equals(rarity)) {
                    stack.set(DataComponents.RARITY, value);
                    break;
                }
            }
        }

        Integer dye = intOrNull(patch.get("dye"));
        if (dye != null) {
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(dye & 0xFFFFFF));
        }

        Map<String, Object> model = map(patch.get("customModelData"));
        if (model != null) {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(
                    floats(model.get("floats")), bools(model.get("flags")),
                    strings(model.get("strings")), ints(model.get("colors"))));
        }

        errors.addAll(applyEnchantments(stack, registries, patch.get("enchantments"),
                DataComponents.ENCHANTMENTS));
        errors.addAll(applyEnchantments(stack, registries, patch.get("storedEnchantments"),
                DataComponents.STORED_ENCHANTMENTS));

        Map<String, Object> potion = map(patch.get("potion"));
        if (potion != null) {
            PotionContents contents = buildPotion(registries, potion, errors);
            if (contents != null) stack.set(DataComponents.POTION_CONTENTS, contents);
        }

        Object rawAttributes = patch.get("attributes");
        if (rawAttributes instanceof List<?> list && !list.isEmpty()) {
            ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
            int index = 0;
            for (Object item : list) {
                index++;
                if (!(item instanceof Map<?, ?> entry)) continue;
                Map<String, Object> row = (Map<String, Object>) entry;
                String attributeId = str(row.get("id"));
                Identifier identifier = Identifier.tryParse(attributeId);
                if (identifier == null) {
                    errors.add("属性 ID 无效：" + attributeId);
                    continue;
                }
                Holder<Attribute> holder = registries.lookupOrThrow(Registries.ATTRIBUTE)
                        .get(ResourceKey.create(Registries.ATTRIBUTE, identifier)).orElse(null);
                if (holder == null) {
                    errors.add("未知属性：" + attributeId);
                    continue;
                }
                double amount = doubleOr(row.get("amount"), 0.0);
                AttributeModifier.Operation operation = operation(str(row.get("operation")));
                EquipmentSlotGroup slot = slotGroup(str(row.get("slot")));
                String modifierId = str(row.get("modifierId"));
                if (modifierId.isBlank()) {
                    modifierId = "samsara:web_" + index + "_"
                            + UUID.randomUUID().toString().substring(0, 8);
                }
                Identifier modifierKey = Identifier.tryParse(modifierId);
                if (modifierKey == null) modifierKey = Identifier.fromNamespaceAndPath("samsara",
                        "web_" + index + "_" + UUID.randomUUID().toString().substring(0, 8));
                builder.add(holder, new AttributeModifier(modifierKey, amount, operation), slot);
            }
            stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
        }
        return errors;
    }

    private static List<String> applyEnchantments(ItemStack stack, RegistryAccess registries,
                                                  Object raw, net.minecraft.core.component.DataComponentType<ItemEnchantments> type) {
        List<String> errors = new ArrayList<>();
        if (!(raw instanceof List<?> list) || list.isEmpty()) return errors;
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) entry;
            String enchantmentId = str(row.get("id"));
            Identifier identifier = Identifier.tryParse(enchantmentId);
            if (identifier == null) {
                errors.add("附魔 ID 无效：" + enchantmentId);
                continue;
            }
            Holder<Enchantment> holder = registries.lookupOrThrow(Registries.ENCHANTMENT)
                    .get(ResourceKey.create(Registries.ENCHANTMENT, identifier)).orElse(null);
            if (holder == null) {
                errors.add("未知附魔：" + enchantmentId);
                continue;
            }
            int level = intOr(row.get("level"), 1);
            if (level < 1) level = 1;
            mutable.set(holder, level);
        }
        if (!mutable.keySet().isEmpty()) stack.set(type, mutable.toImmutable());
        return errors;
    }

    private static PotionContents buildPotion(RegistryAccess registries, Map<String, Object> potion,
                                              List<String> errors) {
        String potionId = str(potion.get("id"));
        Optional<Holder<Potion>> potionHolder = Optional.empty();
        if (!potionId.isBlank()) {
            Identifier identifier = Identifier.tryParse(potionId);
            if (identifier == null) {
                errors.add("药水 ID 无效：" + potionId);
            } else {
                potionHolder = registries.lookupOrThrow(Registries.POTION)
                        .get(ResourceKey.create(Registries.POTION, identifier))
                        .map(holder -> (Holder<Potion>) holder);
                if (potionHolder.isEmpty()) errors.add("未知药水：" + potionId);
            }
        }
        Integer customColor = intOrNull(potion.get("customColor"));
        List<MobEffectInstance> effects = new ArrayList<>();
        if (potion.get("effects") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) entry;
                String effectId = str(row.get("id"));
                Identifier identifier = Identifier.tryParse(effectId);
                if (identifier == null) {
                    errors.add("效果 ID 无效：" + effectId);
                    continue;
                }
                Holder<net.minecraft.world.effect.MobEffect> holder =
                        registries.lookupOrThrow(Registries.MOB_EFFECT)
                                .get(ResourceKey.create(Registries.MOB_EFFECT, identifier)).orElse(null);
                if (holder == null) {
                    errors.add("未知效果：" + effectId);
                    continue;
                }
                int duration = Math.max(1, intOr(row.get("duration"), 200));
                int amplifier = Math.max(0, intOr(row.get("amplifier"), 0));
                boolean ambient = Boolean.TRUE.equals(row.get("ambient"));
                boolean visible = !Boolean.FALSE.equals(row.get("visible"));
                effects.add(new MobEffectInstance(holder, duration, amplifier, ambient, visible));
            }
        }
        if (potionHolder.isEmpty() && effects.isEmpty() && customColor == null) return null;
        return new PotionContents(potionHolder,
                customColor == null ? Optional.empty() : Optional.of(customColor & 0xFFFFFF),
                effects, Optional.empty());
    }

    // ---- 枚举 schema ----

    public static Map<String, Object> schema(RegistryAccess registries) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> enchantments = new ArrayList<>();
        registries.lookupOrThrow(Registries.ENCHANTMENT).listElements().forEach(holder -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", holder.key().identifier().toString());
            row.put("name", holder.value().description().getString());
            row.put("maxLevel", holder.value().getMaxLevel());
            enchantments.add(row);
        });
        enchantments.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
        out.put("enchantments", enchantments);

        List<Map<String, Object>> potions = new ArrayList<>();
        registries.lookupOrThrow(Registries.POTION).listElements().forEach(holder -> {
            Map<String, Object> row = new LinkedHashMap<>();
            String id = holder.key().identifier().toString();
            row.put("id", id);
            row.put("name", pretty(id));
            potions.add(row);
        });
        potions.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
        out.put("potions", potions);

        List<Map<String, Object>> effects = new ArrayList<>();
        registries.lookupOrThrow(Registries.MOB_EFFECT).listElements().forEach(holder -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", holder.key().identifier().toString());
            row.put("name", holder.value().getDisplayName().getString());
            effects.add(row);
        });
        effects.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
        out.put("effects", effects);

        List<Map<String, Object>> attributes = new ArrayList<>();
        registries.lookupOrThrow(Registries.ATTRIBUTE).listElements().forEach(holder -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", holder.key().identifier().toString());
            row.put("name", Component.translatable(holder.value().getDescriptionId()).getString());
            row.put("defaultValue", holder.value().getDefaultValue());
            attributes.add(row);
        });
        attributes.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
        out.put("attributes", attributes);

        List<Map<String, Object>> rarities = new ArrayList<>();
        for (Rarity rarity : Rarity.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rarity.getSerializedName());
            row.put("name", rarity.getSerializedName());
            rarities.add(row);
        }
        out.put("rarities", rarities);

        List<Map<String, Object>> operations = new ArrayList<>();
        for (AttributeModifier.Operation operation : AttributeModifier.Operation.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", operation.getSerializedName());
            row.put("name", operation.getSerializedName());
            operations.add(row);
        }
        out.put("operations", operations);

        List<Map<String, Object>> slots = new ArrayList<>();
        for (EquipmentSlotGroup slot : EquipmentSlotGroup.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", slot.getSerializedName());
            row.put("name", slot.getSerializedName());
            slots.add(row);
        }
        out.put("slots", slots);
        return out;
    }

    // ---- 工具 ----

    private static String key(Holder<?> holder) {
        return holder.unwrapKey().map(ItemStackModel::key).orElse("");
    }

    private static String key(ResourceKey<?> key) {
        return key.identifier().toString();
    }

    private static String pretty(String id) {
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.replace('_', ' ');
    }

    private static AttributeModifier.Operation operation(String value) {
        for (AttributeModifier.Operation operation : AttributeModifier.Operation.values()) {
            if (operation.getSerializedName().equals(value)) return operation;
        }
        return AttributeModifier.Operation.ADD_VALUE;
    }

    private static EquipmentSlotGroup slotGroup(String value) {
        for (EquipmentSlotGroup slot : EquipmentSlotGroup.values()) {
            if (slot.getSerializedName().equals(value)) return slot;
        }
        return EquipmentSlotGroup.ANY;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double doubleOr(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) out.add(item == null ? "" : String.valueOf(item));
        }
        return out;
    }

    private static List<Float> floats(Object value) {
        List<Float> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number number) out.add(number.floatValue());
            }
        }
        return out;
    }

    private static List<Boolean> bools(Object value) {
        List<Boolean> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) out.add(Boolean.TRUE.equals(item));
        }
        return out;
    }

    private static List<Integer> ints(Object value) {
        List<Integer> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number number) out.add(number.intValue());
            }
        }
        return out;
    }
}
