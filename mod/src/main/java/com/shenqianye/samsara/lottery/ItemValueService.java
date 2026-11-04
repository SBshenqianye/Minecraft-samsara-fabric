package com.shenqianye.samsara.lottery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 物品价值表（VP，Value Point）。
 *
 * <p>算法来源：
 * <ol>
 *   <li>基础锚点：铁锭 = 1 VP，金锭 = 4，钻石 = 32，绿宝石 = 64，下界合金锭 = 128；</li>
 *   <li>原版配方比例：木板/木棍/方块按 1/4、1/9 等可逆关系推导；</li>
 *   <li>工具/护甲按材料成本 + 少量加工费；</li>
 *   <li>战利品/稀有物品手工锚点；</li>
 *   <li>未知物品默认 0（不收），由 {@code /samsara lottery value missing} 排查。</li>
 * </ol>
 *
 * <p>本类只做“价签”，不产生 VP 余额；所有支付/奖池/保险仍是实物 ItemStack。
 * 村民交易、刷怪塔、复制 bug 的套利按服主口径接受，不做严格闭包。
 */
public final class ItemValueService {
    private static ItemValueService instance;

    private static final double ENCHANT_PER_LEVEL = 8.0;

    /** 常见物品锚点（完整表由构建期生成器扩展；这里覆盖高频交易物）。 */
    private static final Map<String, Double> BASE = new LinkedHashMap<>();
    /** 材料 → 单位价值，用于工具/护甲/方块推导。 */
    private static final Map<String, Double> MATERIALS = new LinkedHashMap<>();
    /** 中文名 + 拼音（构建期由 zh_cn.json 生成，随 Web 资源打包）。 */
    private static final Map<String, Map<String, Object>> LANG = loadLang();

    static {
        put("minecraft:iron_ingot", 1.0);
        put("minecraft:iron_nugget", 1.0 / 9.0);
        put("minecraft:gold_ingot", 4.0);
        put("minecraft:gold_nugget", 4.0 / 9.0);
        put("minecraft:copper_ingot", 0.5);
        put("minecraft:netherite_ingot", 128.0);
        put("minecraft:netherite_scrap", 28.0);
        put("minecraft:diamond", 32.0);
        put("minecraft:emerald", 64.0);
        put("minecraft:coal", 0.125);
        put("minecraft:charcoal", 0.125);
        put("minecraft:redstone", 0.25);
        put("minecraft:lapis_lazuli", 0.5);
        put("minecraft:quartz", 0.5);
        put("minecraft:amethyst_shard", 0.5);
        put("minecraft:echo_shard", 16.0);
        put("minecraft:nether_star", 4096.0);
        put("minecraft:elytra", 2048.0);
        put("minecraft:totem_of_undying", 1024.0);
        put("minecraft:shulker_shell", 512.0);
        put("minecraft:enchanted_golden_apple", 2048.0);
        put("minecraft:golden_apple", 8.0);
        put("minecraft:netherite_upgrade_smithing_template", 256.0);
        put("minecraft:trident", 256.0);
        put("minecraft:heart_of_the_sea", 256.0);
        put("minecraft:nautilus_shell", 64.0);
        put("minecraft:phantom_membrane", 16.0);
        put("minecraft:gunpowder", 0.5);
        put("minecraft:string", 0.25);
        put("minecraft:feather", 0.125);
        put("minecraft:leather", 0.5);
        put("minecraft:rabbit_hide", 0.25);
        put("minecraft:slime_ball", 0.5);
        put("minecraft:blaze_rod", 2.0);
        put("minecraft:ender_pearl", 4.0);
        put("minecraft:ender_eye", 8.0);
        put("minecraft:ghast_tear", 8.0);
        put("minecraft:magma_cream", 2.0);
        put("minecraft:experience_bottle", 4.0);
        put("minecraft:honey_bottle", 1.0);
        put("minecraft:sugar", 0.125);
        put("minecraft:paper", 0.25);
        put("minecraft:book", 1.0);
        put("minecraft:name_tag", 32.0);
        put("minecraft:saddle", 16.0);
        put("minecraft:lead", 2.0);
        put("minecraft:bread", 0.5);
        put("minecraft:cooked_beef", 1.0);
        put("minecraft:cooked_porkchop", 1.0);
        put("minecraft:golden_carrot", 0.5);
        put("minecraft:torch", 0.05);
        put("minecraft:arrow", 0.05);
        put("minecraft:glass", 0.05);
        put("minecraft:obsidian", 1.0);
        put("minecraft:crying_obsidian", 4.0);
        put("minecraft:ancient_debris", 112.0);
        put("minecraft:sponge", 32.0);
        put("minecraft:wet_sponge", 8.0);
        put("minecraft:ice", 0.05);
        put("minecraft:packed_ice", 0.2);
        put("minecraft:blue_ice", 0.8);
        put("minecraft:dragon_egg", 65536.0);
        put("minecraft:dragon_head", 4096.0);
        put("minecraft:wither_skeleton_skull", 512.0);
        put("minecraft:netherite_helmet", 5 * 128.0);
        put("minecraft:netherite_chestplate", 8 * 128.0);
        put("minecraft:netherite_leggings", 7 * 128.0);
        put("minecraft:netherite_boots", 4 * 128.0);
        put("minecraft:netherite_sword", 2 * 128.0 + 0.0625);
        put("minecraft:netherite_pickaxe", 3 * 128.0 + 0.125);
        put("minecraft:netherite_axe", 3 * 128.0 + 0.125);
        put("minecraft:netherite_shovel", 128.0 + 0.125);
        put("minecraft:netherite_hoe", 2 * 128.0 + 0.125);

        // 原木 / 木板 / 木棍：可逆配方关系
        for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
                "mangrove", "cherry", "bamboo", "crimson", "warped")) {
            put("minecraft:" + wood + "_log", 0.5);
            put("minecraft:stripped_" + wood + "_log", 0.5);
            put("minecraft:" + wood + "_planks", 0.125);
            put("minecraft:" + wood + "_wood", 0.5);
            put("minecraft:stripped_" + wood + "_wood", 0.5);
        }
        put("minecraft:stick", 0.0625);
        put("minecraft:cobblestone", 0.01);
        put("minecraft:cobbled_deepslate", 0.01);
        put("minecraft:stone", 0.02);
        put("minecraft:dirt", 0.01);
        put("minecraft:sand", 0.01);
        put("minecraft:gravel", 0.01);
        put("minecraft:netherrack", 0.01);
        put("minecraft:end_stone", 0.02);

        // 材料表（工具/护甲推导）
        MATERIALS.put("wooden", 0.5);
        MATERIALS.put("stone", 0.05);
        MATERIALS.put("iron", 1.0);
        MATERIALS.put("golden", 4.0);
        MATERIALS.put("diamond", 32.0);
        MATERIALS.put("netherite", 128.0);
    }

    private ItemValueService() {
    }

    public static synchronized ItemValueService getInstance() {
        if (instance == null) {
            instance = new ItemValueService();
        }
        return instance;
    }

    private static void put(String id, double value) {
        BASE.put(id, value);
    }

    private static Map<String, Map<String, Object>> loadLang() {
        Map<String, Map<String, Object>> out = new HashMap<>();
        try (InputStream in = ItemValueService.class.getResourceAsStream("/web/item-lang.json")) {
            if (in == null) {
                return out;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject items = root.getAsJsonObject("items");
            for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("zh", value.get("zh").getAsString());
                row.put("py", value.get("py").getAsString());
                row.put("initials", value.get("initials").getAsString());
                List<String> syllables = new ArrayList<>();
                for (JsonElement syllable : value.getAsJsonArray("syllables")) {
                    syllables.add(syllable.getAsString());
                }
                row.put("syllables", syllables);
                out.put(entry.getKey(), row);
            }
        } catch (Exception e) {
            // 语言资源缺失时退回英文名，不影响价值/交易。
        }
        return out;
    }

    /** 把中文名与拼音合并进任意物品行（价值表、基金列表共用）。 */
    public void applyLang(Map<String, Object> row, String itemId) {
        Map<String, Object> lang = LANG.get(itemId);
        if (lang != null) {
            row.putAll(lang);
        } else {
            row.putIfAbsent("zh", String.valueOf(row.getOrDefault("name", itemId)));
            row.putIfAbsent("py", "");
            row.putIfAbsent("initials", "");
            row.putIfAbsent("syllables", List.of());
        }
    }

    /** 按物品 ID 查基础价值（不含附魔/耐久，供测试与命令使用）。 */
    public double unitValue(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return 0.0;
        }
        return Math.max(0.0, BASE.getOrDefault(itemId, derive(itemId)));
    }

    /** 单件物品价值（不含数量）。 */
    public double unitValue(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0.0;
        }
        String id = idOf(stack.getItem());
        double base = unitValue(id);
        if (base <= 0.0) {
            return 0.0;
        }
        double enchant = enchantValue(stack);
        double durability = durabilityFactor(stack);
        return Math.max(0.0, (base + enchant) * durability);
    }

    /** 整堆价值。 */
    public double valueOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0.0;
        }
        return unitValue(stack) * stack.getCount();
    }

    /**
     * 供 Web 价值表使用：所有可收物品的单价（不含附魔/耐久），按价值降序。
     * 未知物品（0 VP）不返回，避免把整张注册表倒给前端。
     */
    public List<Map<String, Object>> valueTable() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            String id = idOf(item);
            double value = unitValue(id);
            if (value <= 0.0) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", stack.getHoverName().getString());
            row.put("icon", iconName(id));
            row.put("value", value);
            row.put("category", categoryOf(id));
            applyLang(row, id);
            rows.add(row);
        }
        rows.sort((a, b) -> {
            int byValue = Double.compare(((Number) b.get("value")).doubleValue(),
                    ((Number) a.get("value")).doubleValue());
            return byValue != 0 ? byValue : String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id")));
        });
        return rows;
    }

    private static String iconName(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private static String categoryOf(String id) {
        String path = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        if (path.endsWith("_sword") || path.endsWith("_pickaxe") || path.endsWith("_axe")
                || path.endsWith("_shovel") || path.endsWith("_hoe")) {
            return "工具";
        }
        if (path.endsWith("_helmet") || path.endsWith("_chestplate")
                || path.endsWith("_leggings") || path.endsWith("_boots")) {
            return "护甲";
        }
        if (path.endsWith("_block")) {
            return "方块";
        }
        if (path.contains("apple") || path.contains("bread") || path.contains("beef")
                || path.contains("porkchop") || path.contains("carrot") || path.contains("potato")
                || path.contains("chicken") || path.contains("mutton") || path.contains("cod")
                || path.contains("salmon") || path.contains("cookie") || path.contains("cake")
                || path.contains("stew") || path.contains("soup")) {
            return "食物";
        }
        if (path.endsWith("_ingot") || path.endsWith("_nugget") || path.endsWith("_scrap")
                || path.equals("diamond") || path.equals("emerald") || path.equals("coal")
                || path.equals("charcoal") || path.equals("redstone") || path.equals("lapis_lazuli")
                || path.equals("quartz") || path.equals("amethyst_shard") || path.equals("echo_shard")) {
            return "材料";
        }
        return "其他";
    }

    public double valueOf(List<ItemStack> stacks) {
        double total = 0.0;
        for (ItemStack stack : stacks) {
            total += valueOf(stack);
        }
        return total;
    }

    public boolean acceptable(ItemStack stack) {
        return unitValue(stack) > 0.0;
    }

    public String idOf(Item item) {
        Identifier key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "" : key.toString();
    }

    /** 附魔溢价：每级固定 8 VP；高级附魔靠等级堆叠。 */
    public double enchantValue(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0.0;
        }
        int levels = 0;
        for (Map.Entry<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>, Integer> e
                : stack.getEnchantments().entrySet()) {
            Integer level = e.getValue();
            if (level != null && level > 0) {
                levels += level;
            }
        }
        return levels * ENCHANT_PER_LEVEL;
    }

    /** 耐久比例；新物品 1.0，濒临损坏最低 0.25。 */
    public double durabilityFactor(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) {
            return 1.0;
        }
        int max = stack.getMaxDamage();
        if (max <= 0) {
            return 1.0;
        }
        double remain = 1.0 - (stack.getDamageValue() / (double) max);
        return Math.max(0.25, Math.min(1.0, remain));
    }

    /** 按命名/材料推导工具、护甲、方块、矿石等价值；未知返回 0。 */
    private double derive(String id) {
        String path = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        if (path.endsWith("_block")) {
            String material = path.substring(0, path.length() - "_block".length());
            Double unit = MATERIALS.get(material);
            if (unit != null) {
                return unit * 9.0;
            }
            Double anchored = BASE.get("minecraft:" + material + "_ingot");
            if (anchored != null) {
                return anchored * 9.0;
            }
            anchored = BASE.get("minecraft:" + material);
            if (anchored != null) {
                return anchored * 9.0;
            }
        }
        if (path.endsWith("_ore")) {
            String material = path.substring(0, path.length() - "_ore".length());
            Double unit = BASE.get("minecraft:" + material);
            if (unit != null) {
                return unit * 1.2 + 0.05;
            }
            Double ingot = BASE.get("minecraft:" + material + "_ingot");
            if (ingot != null) {
                return ingot * 1.2 + 0.05;
            }
        }
        for (Map.Entry<String, Double> e : MATERIALS.entrySet()) {
            String material = e.getKey();
            double unit = e.getValue();
            String prefix = material + "_";
            if (!path.startsWith(prefix)) {
                continue;
            }
            String kind = path.substring(prefix.length());
            switch (kind) {
                case "sword" -> {
                    return unit * 2.0 + 0.0625;
                }
                case "pickaxe", "axe" -> {
                    return unit * 3.0 + 0.125;
                }
                case "shovel" -> {
                    return unit + 0.125;
                }
                case "hoe" -> {
                    return unit * 2.0 + 0.125;
                }
                case "helmet" -> {
                    return unit * 5.0;
                }
                case "chestplate" -> {
                    return unit * 8.0;
                }
                case "leggings" -> {
                    return unit * 7.0;
                }
                case "boots" -> {
                    return unit * 4.0;
                }
                default -> {
                    // continue
                }
            }
        }
        if (path.endsWith("_planks")) {
            return 0.125;
        }
        if (path.endsWith("_log") || path.endsWith("_wood")) {
            return 0.5;
        }
        return 0.0;
    }

    public String format(double value) {
        if (value >= 1000) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (value >= 10) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
