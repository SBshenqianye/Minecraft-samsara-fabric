package com.shenqianye.samsara.identity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.shenqianye.samsara.lottery.ItemValueService;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** 只读查看某个 data UUID 的玩家数据与备份。 */
public final class IdentityDataInspector {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().create();

    private static final Map<String, String> ADV_ZH = loadAdvZh();

    private static Map<String, String> loadAdvZh() {
        try (InputStream in = IdentityDataInspector.class.getResourceAsStream(
                "/assets/samsara/lang/advancements_zh_cn.json")) {
            if (in == null) return Map.of();
            return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() { }.getType());
        } catch (RuntimeException | IOException e) {
            return Map.of();
        }
    }

    private static String advText(Component component) {
        if (component == null) return "";
        if (component.getContents() instanceof TranslatableContents text) {
            String zh = ADV_ZH.get(text.getKey());
            if (zh != null) return zh;
        }
        return component.getString();
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private IdentityDataInspector() {
    }

    public static Map<String, Object> inspect(MinecraftServer server, UUID data) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (data == null) {
            return error("缺少 data UUID");
        }
        IdentityService service = IdentityService.getInstance();
        IdentityDataSync sync = service.dataSync();
        if (sync == null) {
            return error("身份服务尚未初始化");
        }
        Path dataFile = sync.identityData(data);
        Path advancementsFile = sync.identityAdvancements(data);
        Path statsFile = sync.identityStats(data);
        out.put("ok", true);
        out.put("uuid", data.toString());
        out.put("dataFile", dataFile.toString());
        out.put("exists", Files.isRegularFile(dataFile));
        out.put("bytes", size(dataFile));
        out.put("modified", modified(dataFile));
        out.put("advancementsExists", Files.isRegularFile(advancementsFile));
        out.put("statsExists", Files.isRegularFile(statsFile));
        if (!Files.isRegularFile(dataFile)) {
            out.put("message", "该 data UUID 还没有玩家数据文件");
            return out;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(dataFile, NbtAccounter.unlimitedHeap());
            out.put("health", tag.getFloatOr("Health", 20.0f));
            out.put("food", tag.getIntOr("foodLevel", 20));
            out.put("xpLevel", tag.getIntOr("XpLevel", 0));
            out.put("xpTotal", tag.getIntOr("XpTotal", 0));
            out.put("dimension", tag.getStringOr("Dimension", ""));
            out.put("pos", positions(tag.getListOrEmpty("Pos")));
            out.put("inventory", items(server, tag.getListOrEmpty("Inventory")));
            out.put("enderItems", items(server, tag.getListOrEmpty("EnderItems")));
            out.put("effects", effects(tag.getListOrEmpty("active_effects")));
        } catch (IOException | RuntimeException e) {
            out.put("ok", false);
            out.put("message", "读取玩家数据失败：" + e.getMessage());
            return out;
        }
        out.put("advancements", advancements(advancementsFile, server));
        out.put("stats", stats(statsFile));
        return out;
    }

    public static Map<String, Object> backup(UUID data) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (data == null) {
            return error("缺少 data UUID");
        }
        IdentityService service = IdentityService.getInstance();
        IdentityDataSync sync = service.dataSync();
        if (sync == null || service.identityRoot() == null) {
            return error("身份服务尚未初始化");
        }
        Path dir = service.identityRoot().resolve("backups").resolve("data")
                .resolve(data.toString()).resolve(LocalDateTime.now().format(STAMP));
        List<String> copied = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            copyIfExists(sync.identityData(data), dir, copied);
            copyIfExists(sync.identityAdvancements(data), dir, copied);
            copyIfExists(sync.identityStats(data), dir, copied);
        } catch (IOException e) {
            return error("备份失败：" + e.getMessage());
        }
        if (copied.isEmpty()) {
            return error("该 data UUID 没有可备份的数据文件");
        }
        out.put("ok", true);
        out.put("uuid", data.toString());
        out.put("path", dir.toString());
        out.put("files", copied);
        out.put("message", "已备份 " + copied.size() + " 个文件到 " + dir.getFileName());
        return out;
    }

    /**
     * 列出磁盘上所有身份 data UUID（{@code identity/playerdata/*.dat}）。
     *
     * <p>这是「未分组 data UUID 永久的候选池」：文件在就一直在，不依赖前端内存。
     * 附带所属身份组、管理员元数据、文件大小与备份数量，供 Web 端辨识。
     */
    public static Map<String, Object> list(IdentityService service) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (service == null || service.identityRoot() == null) {
            return error("身份服务尚未初始化");
        }
        Path dir = service.identityRoot().resolve("playerdata");
        IdentityConfig config = service.config();
        Map<String, IdentityConfig.Group> owners = new LinkedHashMap<>();
        if (config != null) {
            for (IdentityConfig.Group group : config.groups) {
                if (group != null && group.dataUuid() != null) {
                    owners.put(group.dataUuid().toString(), group);
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Stream<Path> stream = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            stream.filter(path -> path.getFileName().toString().endsWith(".dat"))
                    .forEach(path -> {
                        String file = path.getFileName().toString();
                        String raw = file.substring(0, file.length() - 4);
                        try {
                            UUID.fromString(raw);
                        } catch (RuntimeException e) {
                            return;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("uuid", raw);
                        IdentityConfig.Group group = owners.get(raw);
                        row.put("group", group == null ? null : group.name);
                        IdentityConfig.Label label = config == null ? null
                                : config.dataLabels.get(raw);
                        row.put("label", label == null ? "" : label.name);
                        row.put("note", label == null ? "" : label.note);
                        try {
                            row.put("bytes", Files.size(path));
                            row.put("modified", Files.getLastModifiedTime(path).toMillis());
                        } catch (IOException e) {
                            row.put("bytes", 0);
                            row.put("modified", 0);
                        }
                        row.put("backups", backupCount(service.identityRoot(), raw));
                        rows.add(row);
                    });
        } catch (IOException e) {
            return error("读取身份数据目录失败：" + e.getMessage());
        }
        rows.sort((left, right) -> Long.compare(
                ((Number) right.get("modified")).longValue(),
                ((Number) left.get("modified")).longValue()));
        out.put("ok", true);
        out.put("root", dir.toString());
        out.put("data", rows);
        return out;
    }

    /** 列出某个 data UUID 的备份记录（时间 / 大小 / 路径），最新在前。 */
    public static Map<String, Object> backups(IdentityService service, UUID data) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (data == null) {
            return error("缺少 data UUID");
        }
        if (service == null || service.identityRoot() == null) {
            return error("身份服务尚未初始化");
        }
        Path dir = service.identityRoot().resolve("backups").resolve("data")
                .resolve(data.toString());
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Stream<Path> stream = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            stream.filter(Files::isDirectory).forEach(path -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", path.getFileName().toString());
                row.put("path", path.toString());
                List<String> files = new ArrayList<>();
                long bytes = 0;
                try (Stream<Path> inner = Files.list(path)) {
                    for (Path file : (Iterable<Path>) inner::iterator) {
                        if (!Files.isRegularFile(file)) {
                            continue;
                        }
                        files.add(file.getFileName().toString());
                        bytes += Files.size(file);
                    }
                } catch (IOException ignored) {
                    // 单个备份目录不可读时仍显示记录。
                }
                row.put("files", files);
                row.put("bytes", bytes);
                rows.add(row);
            });
        } catch (IOException e) {
            return error("读取备份目录失败：" + e.getMessage());
        }
        rows.sort((left, right) -> String.valueOf(right.get("id"))
                .compareTo(String.valueOf(left.get("id"))));
        out.put("ok", true);
        out.put("uuid", data.toString());
        out.put("root", dir.toString());
        out.put("backups", rows);
        return out;
    }

    private static int backupCount(Path identityRoot, String uuid) {
        Path dir = identityRoot.resolve("backups").resolve("data").resolve(uuid);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.filter(Files::isDirectory).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static List<Map<String, Object>> items(MinecraftServer server, ListTag list) {
        List<Map<String, Object>> out = new ArrayList<>();
        RegistryAccess registries = server == null ? RegistryAccess.EMPTY : server.registryAccess();
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            ItemStack stack = ItemStack.CODEC.parse(ops, entry)
                    .result().orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) {
                continue;
            }
            String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", entry.getByteOr("Slot", (byte) i));
            row.put("id", id);
            row.put("name", stack.getHoverName().getString());
            int colon = id.indexOf(':');
            row.put("icon", colon >= 0 ? id.substring(colon + 1) : id);
            row.put("count", stack.getCount());
            row.put("value", ItemValueService.getInstance().valueOf(stack));
            out.add(row);
        }
        return out;
    }

    private static List<Double> positions(ListTag list) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getDoubleOr(i, 0.0));
        }
        return out;
    }

    private static List<Map<String, Object>> effects(ListTag list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompoundOrEmpty(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", tag.getStringOr("id", ""));
            row.put("amplifier", tag.getByteOr("amplifier", (byte) 0));
            row.put("duration", tag.getIntOr("duration", 0));
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> advancements(Path file, MinecraftServer server) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", Files.isRegularFile(file));
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            int done = 0;
            List<String> ids = new ArrayList<>();
            java.util.Set<String> doneIds = new java.util.HashSet<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getValue().isJsonObject()
                        && entry.getValue().getAsJsonObject().has("done")
                        && entry.getValue().getAsJsonObject().get("done").getAsBoolean()) {
                    done++;
                    doneIds.add(entry.getKey());
                    if (ids.size() < 80) ids.add(entry.getKey());
                }
            }
            out.put("total", root.size());
            out.put("done", done);
            out.put("ids", ids);
            if (server != null) {
                List<Map<String, Object>> nodes = new ArrayList<>();
                for (AdvancementNode node : server.getAdvancements().tree().nodes()) {
                    AdvancementHolder holder = node.holder();
                    DisplayInfo display = holder.value().display().orElse(null);
                    if (display == null || display.hidden()) {
                        continue;
                    }
                    String id = holder.id().toString();
                    ItemStack icon = display.icon().create();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", id);
                    row.put("title", advText(display.title()));
                    row.put("description", advText(display.description()));
                    row.put("icon", BuiltInRegistries.ITEM.getKey(icon.getItem()).getPath());
                    row.put("frame", display.type().getSerializedName());
                    row.put("x", node.x());
                    row.put("y", node.y());
                    row.put("parent", node.parent() == null ? ""
                            : node.parent().holder().id().toString());
                    row.put("done", doneIds.contains(id));
                    nodes.add(row);
                }
                out.put("nodes", nodes);
            }
        } catch (IOException | RuntimeException e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    private static Map<String, Object> stats(Path file) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", Files.isRegularFile(file));
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            // 落盘格式是 {"stats":{"minecraft:custom":{...}}}，兼容直接平铺的旧格式。
            JsonObject statsRoot = root.has("stats") && root.get("stats").isJsonObject()
                    ? root.getAsJsonObject("stats") : root;
            JsonObject custom = statsRoot.has("minecraft:custom") && statsRoot.get("minecraft:custom").isJsonObject()
                    ? statsRoot.getAsJsonObject("minecraft:custom") : new JsonObject();
            for (String key : List.of("play_time", "deaths", "mob_kills", "player_kills",
                    "walk_one_cm", "sprint_one_cm", "fly_one_cm", "damage_dealt")) {
                // 原版统计键带 minecraft: 前缀；兼容未加前缀的旧文件。
                String prefixed = "minecraft:" + key;
                if (custom.has(prefixed)) {
                    out.put(key, custom.get(prefixed).getAsLong());
                } else if (custom.has(key)) {
                    out.put(key, custom.get(key).getAsLong());
                }
            }
            out.put("custom", numberMap(custom));
            Map<String, Object> items = new LinkedHashMap<>();
            for (String key : List.of("mined", "crafted", "used", "picked_up", "dropped", "broken")) {
                items.put(key, numberMap(category(statsRoot, key)));
            }
            out.put("items", items);
            Map<String, Object> mobs = new LinkedHashMap<>();
            for (String key : List.of("killed", "killed_by")) {
                mobs.put(key, numberMap(category(statsRoot, key)));
            }
            out.put("mobs", mobs);
            out.put("categories", statsRoot.keySet().size());
        } catch (IOException | RuntimeException e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    private static JsonObject category(JsonObject statsRoot, String name) {
        String key = "minecraft:" + name;
        if (statsRoot.has(key) && statsRoot.get(key).isJsonObject()) {
            return statsRoot.getAsJsonObject(key);
        }
        return statsRoot.has(name) && statsRoot.get(name).isJsonObject()
                ? statsRoot.getAsJsonObject(name) : new JsonObject();
    }

    private static Map<String, Long> numberMap(JsonObject source) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            try {
                if (entry.getValue().isJsonPrimitive()) {
                    out.put(entry.getKey(), entry.getValue().getAsLong());
                }
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    private static void copyIfExists(Path source, Path dir, List<String> copied) throws IOException {
        if (!Files.isRegularFile(source)) {
            return;
        }
        Files.copy(source, dir.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        copied.add(source.getFileName().toString());
    }

    private static long size(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long modified(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }
}
