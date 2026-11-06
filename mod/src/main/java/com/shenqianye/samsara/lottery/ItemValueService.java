package com.shenqianye.samsara.lottery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

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
 *   <li>潜影盒/收纳袋等容器：容器自身价值 + 内容物价值（递归，限深防止恶意嵌套）；</li>
 *   <li>未知物品默认 0（不收），由 {@code /samsara lottery value missing} 排查。</li>
 * </ol>
 *
 * <p>本类只做“价签”，不产生 VP 余额；所有支付/奖池/保险仍是实物 ItemStack。
 * 村民交易、刷怪塔、复制 bug 的套利按服主口径接受，不做严格闭包。
 */
public final class ItemValueService {
    private static ItemValueService instance;

    private static final double ENCHANT_PER_LEVEL = 8.0;
    /** 容器内容递归上限：防止异常深层嵌套拖垮估值；超过后只计当前层物品自身价值。 */
    private static final int MAX_CONTAINER_DEPTH = 8;

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
        put("minecraft:shulker_box", 2 * 512.0 + 1.0);
        put("minecraft:bundle", 0.75);
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
        double gold = goldUnitValue(itemId);
        if (gold >= 0.0) {
            return Math.max(0.0, gold);
        }
        return Math.max(0.0, BASE.getOrDefault(itemId, derive(itemId)));
    }

    /** 由乐透模块注入的金价提供器；单元测试未注入时使用静态锚点。 */
    @FunctionalInterface
    public interface GoldValueProvider {
        /** 返回金块 VP；返回负数表示不使用动态金价。 */
        double blockValue();
    }

    private static volatile GoldValueProvider goldProvider;
    private static volatile boolean goldCraftedLinkEnabled;

    public static void setGoldValueProvider(GoldValueProvider provider) {
        goldProvider = provider;
    }

    public static void setGoldCraftedLinkEnabled(boolean enabled) {
        goldCraftedLinkEnabled = enabled;
    }

    /** 金粒/金锭/金块及纯金合成物按国际金价指数缩放；未启用返回 -1。 */
    private double goldUnitValue(String itemId) {
        GoldValueProvider provider = goldProvider;
        if (provider == null) {
            return -1.0;
        }
        String path = itemId.startsWith("minecraft:")
                ? itemId.substring("minecraft:".length()) : itemId;
        boolean rawGold = path.equals("gold_block") || path.equals("gold_ingot")
                || path.equals("gold_nugget");
        if (!rawGold && !goldCraftedLinkEnabled) {
            return -1.0;
        }
        double block;
        try {
            block = provider.blockValue();
        } catch (RuntimeException e) {
            return -1.0;
        }
        if (block < 0.0) {
            return -1.0;
        }
        double ingot = block / 9.0;
        double nugget = block / 81.0;
        if (path.equals("gold_block")) {
            return block;
        }
        if (path.equals("gold_ingot")) {
            return ingot;
        }
        if (path.equals("gold_nugget")) {
            return nugget;
        }
        if (!goldCraftedLinkEnabled) {
            return -1.0;
        }
        // 纯金合成物：按配方递归到金锭/金粒，再叠加少量非金材料。
        return switch (path) {
            case "golden_apple" -> 8.0 * ingot + 0.5;
            case "golden_carrot" -> 8.0 * nugget + 0.125;
            case "glistering_melon_slice" -> 8.0 * nugget + 0.125;
            case "golden_sword" -> 2.0 * ingot + 0.0625;
            case "golden_pickaxe", "golden_axe" -> 3.0 * ingot + 0.125;
            case "golden_shovel" -> ingot + 0.125;
            case "golden_hoe" -> 2.0 * ingot + 0.125;
            case "golden_helmet" -> 5.0 * ingot;
            case "golden_chestplate" -> 8.0 * ingot;
            case "golden_leggings" -> 7.0 * ingot;
            case "golden_boots" -> 4.0 * ingot;
            case "clock" -> 4.0 * ingot + 0.25;
            case "powered_rail" -> 6.0 * ingot + 0.0625 + 0.25;
            case "raw_gold" -> ingot;
            case "gold_ore", "deepslate_gold_ore" -> ingot * 1.2 + 0.05;
            case "nether_gold_ore" -> 4.0 * nugget;
            default -> -1.0;
        };
    }

    /** 该物品是否随国际金价联动（供 Web 价值表标记）。 */
    public boolean isGoldLinked(String itemId) {
        return itemId != null && goldUnitValue(itemId) >= 0.0;
    }

    /** 单件物品价值（不含数量），容器会递归计入内容物。 */
    public double unitValue(ItemStack stack) {
        return unitValue(stack, 0);
    }

    private double unitValue(ItemStack stack, int depth) {
        if (stack == null || stack.isEmpty()) {
            return 0.0;
        }
        String id = idOf(stack.getItem());
        double base = unitValue(id);
        // 容器自身没有价签时仍要计算内容物；普通未知物品保持 0。
        double own = base <= 0.0 ? 0.0 : (base + enchantValue(stack)) * durabilityFactor(stack);
        double contents = depth < MAX_CONTAINER_DEPTH ? containerValue(stack, depth + 1) : 0.0;
        return Math.max(0.0, own + contents);
    }

    /** 整堆价值（含潜影盒/收纳袋等内容物，按堆叠数量放大）。 */
    public double valueOf(ItemStack stack) {
        return valueOf(stack, 0);
    }

    private double valueOf(ItemStack stack, int depth) {
        if (stack == null || stack.isEmpty()) {
            return 0.0;
        }
        return unitValue(stack, depth) * stack.getCount();
    }

    /** 容器内容物价值；兼容潜影盒 CONTAINER 与收纳袋 BUNDLE_CONTENTS。 */
    private double containerValue(ItemStack stack, int depth) {
        if (depth > MAX_CONTAINER_DEPTH) {
            return 0.0;
        }
        double total = 0.0;
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            total += container.nonEmptyItemCopyStream()
                    .mapToDouble(child -> valueOf(child, depth))
                    .sum();
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            total += bundle.itemCopies()
                    .mapToDouble(child -> valueOf(child, depth))
                    .sum();
        }
        return total;
    }

    private volatile List<Map<String, Object>> allCache;

    /** 全部注册物品（含 0 VP 的不可收物品）；懒加载价值表用。 */
    public List<Map<String, Object>> allItems() {
        List<Map<String, Object>> cached = allCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (allCache != null) {
                return allCache;
            }
            allCache = buildAllItems();
            return allCache;
        }
    }

    private List<Map<String, Object>> buildAllItems() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            String id = idOf(item);
            double value = unitValue(id);
            ItemStack stack = new ItemStack(item);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", stack.getHoverName().getString());
            row.put("icon", iconName(id));
            row.put("value", value);
            row.put("acceptable", value > 0.0);
            row.put("goldLinked", isGoldLinked(id));
            row.put("category", categoryOf(id));
            applyLang(row, id);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 懒加载 + 服务端搜索：中文/英文/ID/分类、全拼、首字母、逐字拼音前缀。
     * 返回当前页与匹配总数，避免一次把 1000+ 物品倒给前端。
     */
    public Map<String, Object> queryItems(String query, String category, String sort,
                                          int offset, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String cat = category == null ? "" : category;
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : allItems()) {
            if (!cat.isBlank() && !cat.equals(row.get("category"))) {
                continue;
            }
            if (!q.isEmpty() && !matches(row, q)) {
                continue;
            }
            filtered.add(row);
        }
        String mode = sort == null ? "value-desc" : sort;
        filtered.sort((a, b) -> {
            if ("name".equals(mode)) {
                String na = String.valueOf(a.getOrDefault("zh", a.get("name")));
                String nb = String.valueOf(b.getOrDefault("zh", b.get("name")));
                int cmp = na.compareToIgnoreCase(nb);
                return cmp != 0 ? cmp : String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id")));
            }
            double va = ((Number) a.get("value")).doubleValue();
            double vb = ((Number) b.get("value")).doubleValue();
            int cmp = "value-asc".equals(mode) ? Double.compare(va, vb) : Double.compare(vb, va);
            return cmp != 0 ? cmp : String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id")));
        });
        int safeLimit = Math.max(1, Math.min(200, limit));
        int safeOffset = Math.max(0, offset);
        int from = Math.min(safeOffset, filtered.size());
        int to = Math.min(filtered.size(), from + safeLimit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", filtered.size());
        out.put("offset", safeOffset);
        out.put("limit", safeLimit);
        out.put("values", filtered.subList(from, to));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static boolean matches(Map<String, Object> row, String query) {
        String text = (String.valueOf(row.getOrDefault("name", "")) + ' '
                + String.valueOf(row.getOrDefault("zh", "")) + ' '
                + String.valueOf(row.getOrDefault("id", "")) + ' '
                + String.valueOf(row.getOrDefault("category", ""))).toLowerCase(Locale.ROOT);
        if (text.contains(query)) {
            return true;
        }
        String py = String.valueOf(row.getOrDefault("py", ""));
        String initials = String.valueOf(row.getOrDefault("initials", ""));
        if (py.contains(query) || initials.contains(query)) {
            return true;
        }
        Object syllables = row.get("syllables");
        if (!(syllables instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        boolean[] dp = new boolean[query.length() + 1];
        dp[0] = true;
        for (Object raw : list) {
            String syllable = String.valueOf(raw);
            if (syllable.isEmpty()) continue;
            boolean[] next = new boolean[query.length() + 1];
            for (int j = 0; j <= query.length(); j++) {
                if (dp[j]) next[j] = true;
            }
            for (int j = 0; j < query.length(); j++) {
                if (!dp[j]) continue;
                for (int k = 1; k <= syllable.length() && j + k <= query.length(); k++) {
                    if (query.startsWith(syllable.substring(0, k), j)) {
                        next[j + k] = true;
                    }
                }
            }
            dp = next;
        }
        return dp[query.length()];
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
        // 潜影盒：2 个潜影壳 + 1 个箱子；所有颜色同价。
        if (path.equals("shulker_box") || path.endsWith("_shulker_box")) {
            return 2.0 * BASE.getOrDefault("minecraft:shulker_shell", 512.0) + 1.0;
        }
        // 收纳袋：皮革 + 线；所有颜色同价。
        if (path.equals("bundle") || path.endsWith("_bundle")) {
            return 0.75;
        }
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
