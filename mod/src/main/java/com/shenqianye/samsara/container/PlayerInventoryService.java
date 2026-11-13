package com.shenqianye.samsara.container;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.lottery.LotteryItemCodec;
import com.shenqianye.samsara.season.PlayerRegistry;
import com.shenqianye.samsara.storage.StoragePaths;
import com.shenqianye.samsara.web.WebAuditService;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家背包 / 末影箱 Web 读写。
 *
 * <p>在线玩家在服务器线程读写内存 Inventory；离线玩家只读 {@code playerdata/<uuid>.dat}，
 * 写入进入 {@code world/samsara/inventory-pending/<uuid>-<scope>.json} 安全队列，
 * 玩家下次 JOIN 或停服前应用。任何写入前都会创建 Web 审计快照。
 *
 * <p>只编辑背包/盔甲/副手/末影箱，不碰光标物品和合成网格。
 */
public final class PlayerInventoryService {
    private static final PlayerInventoryService INSTANCE = new PlayerInventoryService();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    private static final String PENDING_DIR = "inventory-pending";

    private MinecraftServer server;
    private RegistryAccess registries;
    private Path pendingDir;
    private final java.util.Set<UUID> joinPending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private PlayerInventoryService() {
    }

    public static PlayerInventoryService getInstance() {
        return INSTANCE;
    }

    public synchronized void init(MinecraftServer server) {
        this.server = server;
        this.registries = server == null ? null : server.registryAccess();
        this.pendingDir = null;
        if (server != null && StoragePaths.ready()) {
            this.pendingDir = StoragePaths.get().world(PENDING_DIR);
            try {
                Files.createDirectories(pendingDir);
            } catch (IOException e) {
                Samsara.LOGGER.warn("[inventory] 无法创建待写入队列目录: {}", e.getMessage());
            }
        }
    }

    public synchronized void onServerStopping(MinecraftServer server) {
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                applyPending(player, true);
            }
        }
        joinPending.clear();
        this.server = null;
        this.registries = null;
        this.pendingDir = null;
    }

    /**
     * 玩家进入服务器后标记；下一 tick 再应用队列。
     * 部分假玩家/代理会在 JOIN 事件后继续恢复自己的初始背包，立即写入会被覆盖。
     */
    public void onJoin(ServerPlayer player) {
        if (player != null) {
            joinPending.add(player.getUUID());
        }
    }

    /** 每 tick 处理刚加入玩家的待写入队列。 */
    public void tick(MinecraftServer server) {
        if (server == null || joinPending.isEmpty()) return;
        for (UUID uuid : java.util.List.copyOf(joinPending)) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) continue;
            applyPending(player, false);
            joinPending.remove(uuid);
        }
    }

    public Map<String, Object> read(Map<String, String> query) {
        String source = query.getOrDefault("source", "player").trim();
        SlotSpec spec = SlotSpec.from(query.getOrDefault("scope", "inventory"));
        if (spec == null) {
            return error(400, "scope 必须是 inventory、ender 或 armor");
        }
        if ("identity".equals(source)) {
            String dataRef = query.getOrDefault("data", "").trim();
            if (dataRef.isEmpty()) return error(400, "需要 data=<data UUID>");
            return onServer(() -> readIdentityOnThread(dataRef, spec));
        }
        String playerRef = query.getOrDefault("player", "").trim();
        if (playerRef.isEmpty()) {
            return error(400, "需要 player=<uuid|name>");
        }
        return onServer(() -> readOnThread(playerRef, spec));
    }

    public Map<String, Object> write(String body, String actor) {
        Map<String, Object> request = parse(body);
        if (request == null) {
            return error(400, "请求体应为 JSON 对象");
        }
        String source = string(request, "source");
        SlotSpec spec = SlotSpec.from(string(request, "scope"));
        if (spec == null) return error(400, "scope 必须是 inventory、ender 或 armor");
        long revision = longValue(request.get("revision"), -1L);
        List<String> slots = slots(request.get("slots"));
        if (slots == null) return error(400, "slots 必须是字符串数组");
        if (slots.size() != spec.size) {
            return error(400, "slots 长度必须是 " + spec.size + "（" + spec.id + "）");
        }
        if ("identity".equals(source)) {
            String dataRef = string(request, "data");
            if (dataRef.isBlank()) return error(400, "需要 data=<data UUID>");
            return onServer(() -> writeIdentityOnThread(dataRef, spec, revision, slots, actor));
        }
        String playerRef = string(request, "player");
        if (playerRef.isBlank()) return error(400, "需要 player");
        return onServer(() -> writeOnThread(playerRef, spec, revision, slots, actor));
    }

    /** 与容器页相同：把物品 id + count 或已有编码转换成写入编码。 */
    public Map<String, Object> item(String body) {
        Map<String, Object> request = parse(body);
        if (request == null) return error(400, "请求体应为 JSON 对象");
        String encoded = string(request, "encoded");
        String id = string(request, "id");
        int count = intValue(request.get("count"), 1);
        boolean unlimited = Boolean.TRUE.equals(request.get("unlimited"));
        if (count < 1) return error(400, "数量至少为 1");
        return onServer(() -> {
            if (server == null) return error(503, "服务器未启动");
            ItemStack stack;
            if (!encoded.isBlank()) {
                stack = LotteryItemCodec.decode(encoded, registries);
                if (stack.isEmpty()) return error(400, "物品编码无效");
                stack.setCount(count);
            } else {
                var identifier = net.minecraft.resources.Identifier.tryParse(id);
                if (identifier == null) return error(400, "物品 ID 格式不正确");
                var item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
                if (item == null || item == net.minecraft.world.item.Items.AIR) {
                    return error(400, "未知物品：" + id);
                }
                stack = new ItemStack(item, count);
            }
            if (!unlimited && stack.getCount() > stack.getMaxStackSize()) {
                return error(400, "数量超过最大堆叠数 " + stack.getMaxStackSize());
            }
            String result = LotteryItemCodec.encode(stack, registries);
            if (result.isBlank()) return error(500, "物品编码失败");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.putAll(com.shenqianye.samsara.item.ItemStackModel.describe(
                    stack, registries, result));
            return out;
        });
    }

    private Map<String, Object> readOnThread(String playerRef, SlotSpec spec) {
        PlayerRef ref = resolve(playerRef);
        if (ref == null) return error(404, "找不到玩家：" + playerRef);
        ServerPlayer online = ref.online;
        Path file = playerFile(ref.uuid);
        int serverDataVersion = serverDataVersion();
        List<String> slots;
        boolean pendingWrite = false;
        long diskRevision;
        int dataVersion;
        String outSource = "player";
        String outData = "";
        Path queue = queueFile(ref.uuid, spec);

        if (online != null) {
            slots = encodeOnline(online, spec);
            diskRevision = revisionOf(slots);
            dataVersion = serverDataVersion;
            pendingWrite = Files.isRegularFile(queue);
        } else {
            IdentityTarget identity = identityTarget(ref.uuid);
            Path source = identity == null ? file : identity.file();
            if (!Files.isRegularFile(source)) {
                return error(404, "找不到玩家数据：" + ref.uuid);
            }
            try {
                CompoundTag tag = readTag(source);
                dataVersion = tag.getIntOr("DataVersion", 0);
                slots = decodeOffline(tag, spec);
                diskRevision = revisionOf(slots);
            } catch (IOException | RuntimeException e) {
                return error(400, "读取玩家数据失败：" + e.getMessage());
            }
            if (identity != null) {
                // 身份数据是根；不再叠加账号侧的旧排队写入，避免覆盖身份背包。
                outSource = "identity";
                outData = identity.data().toString();
            } else {
                List<String> queued = readQueue(queue, spec);
                if (queued != null) {
                    slots = queued;
                    pendingWrite = true;
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("source", outSource);
        if (!outData.isBlank()) out.put("data", outData);
        out.put("player", ref.model());
        out.put("scope", spec.id);
        out.put("online", online != null);
        out.put("writable", online != null);
        out.put("queueWritable", online == null);
        out.put("pendingWrite", pendingWrite);
        out.put("revision", revisionOf(slots));
        out.put("diskRevision", diskRevision);
        out.put("dataVersion", dataVersion);
        out.put("serverDataVersion", serverDataVersion);
        out.put("slots", slots);
        out.put("items", items(slots, spec));
        return out;
    }

    private Map<String, Object> writeOnThread(String playerRef, SlotSpec spec, long revision,
                                              List<String> slots, String actor) {
        PlayerRef ref = resolve(playerRef);
        if (ref == null) return error(404, "找不到玩家：" + playerRef);
        String validation = validateSlots(slots, spec);
        if (validation != null) return error(400, validation);

        if (ref.online != null) {
            List<String> current = encodeOnline(ref.online, spec);
            long currentRevision = revisionOf(current);
            if (revision != currentRevision) {
                Map<String, Object> conflict = error(409, "背包已被其他操作修改，请重新读取");
                conflict.put("currentRevision", currentRevision);
                return conflict;
            }
            String detail = ref.name + " " + spec.id + " revision=" + revision;
            WebAuditService.get().captureInventory("/api/inventory/write", actor, detail,
                    GSON.toJson(snapshot(ref, spec, current)));
            applyOnline(ref.online, spec, slots);
            List<String> after = encodeOnline(ref.online, spec);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("status", 200);
            out.put("queued", false);
            out.put("player", ref.model());
            out.put("scope", spec.id);
            out.put("online", true);
            out.put("revision", revisionOf(after));
            out.put("slots", after);
            out.put("items", items(after, spec));
            return out;
        }

        IdentityTarget identity = identityTarget(ref.uuid);
        if (identity != null) {
            // 离线账号写身份背包：走 identity/playerdata，自动备份并校验 revision。
            return writeIdentityOnThread(identity.data().toString(), spec, revision, slots, actor);
        }

        Path file = playerFile(ref.uuid);
        if (!Files.isRegularFile(file)) return error(404, "找不到玩家数据：" + ref.uuid);
        List<String> disk;
        int dataVersion;
        try {
            CompoundTag tag = readTag(file);
            dataVersion = tag.getIntOr("DataVersion", 0);
            disk = decodeOffline(tag, spec);
        } catch (IOException | RuntimeException e) {
            return error(400, "读取玩家数据失败：" + e.getMessage());
        }
        int serverDataVersion = serverDataVersion();
        if (dataVersion > 0 && serverDataVersion > 0 && dataVersion != serverDataVersion) {
            WebAuditService.get().captureInventory("/api/inventory/write/rejected", actor,
                    ref.name + " " + spec.id + " DataVersion " + dataVersion + " != "
                            + serverDataVersion, GSON.toJson(snapshot(ref, spec, disk)));
            return error(409, "玩家数据 DataVersion " + dataVersion
                    + " 与服务器 " + serverDataVersion + " 不一致，已拒绝写入");
        }
        Path queue = queueFile(ref.uuid, spec);
        List<String> pending = readQueue(queue, spec);
        List<String> effective = pending == null ? disk : pending;
        long currentRevision = revisionOf(effective);
        if (revision != currentRevision) {
            Map<String, Object> conflict = error(409, "待写入队列已变化，请重新读取");
            conflict.put("currentRevision", currentRevision);
            return conflict;
        }
        Map<String, Object> pendingModel = snapshot(ref, spec, effective);
        pendingModel.put("slots", slots);
        pendingModel.put("revision", revisionOf(slots));
        pendingModel.put("actor", actor);
        pendingModel.put("createdAt", System.currentTimeMillis());
        pendingModel.put("dataVersion", dataVersion);
        if (!writeQueue(queue, pendingModel)) {
            return error(500, "写入待处理队列失败");
        }
        WebAuditService.get().captureInventory("/api/inventory/write/queued", actor,
                ref.name + " " + spec.id + " revision=" + revision,
                GSON.toJson(snapshot(ref, spec, effective)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("status", 200);
        out.put("queued", true);
        out.put("online", false);
        out.put("player", ref.model());
        out.put("scope", spec.id);
        out.put("revision", revisionOf(slots));
        out.put("pendingWrite", true);
        out.put("slots", slots);
        out.put("items", items(slots, spec));
        out.put("message", "玩家不在线，已写入安全队列；下次登录或停服前应用");
        return out;
    }

    // ---- 身份 data UUID 背包（与账号背包共用 UI/槽位） ----

    private Map<String, Object> readIdentityOnThread(String dataRef, SlotSpec spec) {
        UUID data = parseUuid(dataRef);
        if (data == null) return error(400, "data UUID 格式不正确");
        com.shenqianye.samsara.identity.IdentityService identity =
                com.shenqianye.samsara.identity.IdentityService.getInstance();
        com.shenqianye.samsara.identity.IdentityDataSync sync = identity.dataSync();
        if (sync == null) return error(503, "身份服务尚未初始化");
        ServerPlayer online = identity.onlineFor(data);
        Path file = sync.identityData(data);
        List<String> slots;
        boolean pendingWrite = false;
        long diskRevision;
        int dataVersion;
        if (online != null) {
            slots = encodeOnline(online, spec);
            diskRevision = revisionOf(slots);
            dataVersion = serverDataVersion();
        } else {
            if (!Files.isRegularFile(file)) {
                return error(404, "该 data UUID 还没有玩家数据文件");
            }
            try {
                CompoundTag tag = readTag(file);
                dataVersion = tag.getIntOr("DataVersion", 0);
                slots = decodeOffline(tag, spec);
                diskRevision = revisionOf(slots);
            } catch (IOException | RuntimeException e) {
                return error(400, "读取身份数据失败：" + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("source", "identity");
        out.put("data", data.toString());
        out.put("player", new PlayerRef(data, online == null
                ? "" : online.getGameProfile().name(), online).model());
        out.put("scope", spec.id);
        out.put("online", online != null);
        out.put("writable", true);
        out.put("queueWritable", false);
        out.put("pendingWrite", pendingWrite);
        out.put("revision", revisionOf(slots));
        out.put("diskRevision", diskRevision);
        out.put("dataVersion", dataVersion);
        out.put("serverDataVersion", serverDataVersion());
        out.put("slots", slots);
        out.put("items", items(slots, spec));
        return out;
    }

    private Map<String, Object> writeIdentityOnThread(String dataRef, SlotSpec spec, long revision,
                                                      List<String> slots, String actor) {
        UUID data = parseUuid(dataRef);
        if (data == null) return error(400, "data UUID 格式不正确");
        String validation = validateSlots(slots, spec);
        if (validation != null) return error(400, validation);
        com.shenqianye.samsara.identity.IdentityService identity =
                com.shenqianye.samsara.identity.IdentityService.getInstance();
        com.shenqianye.samsara.identity.IdentityDataSync sync = identity.dataSync();
        if (sync == null) return error(503, "身份服务尚未初始化");
        ServerPlayer online = identity.onlineFor(data);
        PlayerRef ref = new PlayerRef(data, online == null ? "" : online.getGameProfile().name(),
                online);
        if (online != null) {
            List<String> current = encodeOnline(online, spec);
            long currentRevision = revisionOf(current);
            if (revision != currentRevision) {
                Map<String, Object> conflict = error(409, "背包已被其他操作修改，请重新读取");
                conflict.put("currentRevision", currentRevision);
                return conflict;
            }
            WebAuditService.get().captureInventory("/api/inventory/identity/write", actor,
                    "data=" + data + " " + spec.id + " revision=" + revision,
                    GSON.toJson(snapshot(ref, spec, current)));
            applyOnline(online, spec, slots);
            List<String> after = encodeOnline(online, spec);
            return identityWriteModel(ref, spec, after, true, false);
        }

        Path file = sync.identityData(data);
        if (!Files.isRegularFile(file)) return error(404, "该 data UUID 还没有玩家数据文件");
        CompoundTag tag;
        List<String> disk;
        int dataVersion;
        try {
            tag = readTag(file);
            dataVersion = tag.getIntOr("DataVersion", 0);
            disk = decodeOffline(tag, spec);
        } catch (IOException | RuntimeException e) {
            return error(400, "读取身份数据失败：" + e.getMessage());
        }
        int serverVersion = serverDataVersion();
        if (dataVersion > 0 && serverVersion > 0 && dataVersion != serverVersion) {
            WebAuditService.get().captureInventory("/api/inventory/identity/write/rejected", actor,
                    "data=" + data + " " + spec.id + " DataVersion " + dataVersion
                            + " != " + serverVersion, GSON.toJson(snapshot(ref, spec, disk)));
            return error(409, "身份数据 DataVersion " + dataVersion
                    + " 与服务器 " + serverVersion + " 不一致，已拒绝写入");
        }
        long currentRevision = revisionOf(disk);
        if (revision != currentRevision) {
            Map<String, Object> conflict = error(409, "身份数据已变化，请重新读取");
            conflict.put("currentRevision", currentRevision);
            return conflict;
        }
        Map<String, Object> backup = com.shenqianye.samsara.identity.IdentityDataInspector.backup(data);
        if (backup.get("ok") != Boolean.TRUE) {
            return error(500, "写入前备份失败：" + backup.getOrDefault("error", "未知错误"));
        }
        try {
            applyOfflineToTag(tag, spec, slots);
            Path temp = file.resolveSibling(file.getFileName() + ".web.tmp");
            NbtIo.writeCompressed(tag, temp);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            return error(500, "写入身份数据失败：" + e.getMessage());
        }
        WebAuditService.get().captureInventory("/api/inventory/identity/write/file", actor,
                "data=" + data + " " + spec.id + " revision=" + revision,
                GSON.toJson(snapshot(ref, spec, disk)));
        return identityWriteModel(ref, spec, slots, false, false);
    }

    private Map<String, Object> identityWriteModel(PlayerRef ref, SlotSpec spec, List<String> slots,
                                                   boolean online, boolean queued) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("status", 200);
        out.put("queued", queued);
        out.put("source", "identity");
        out.put("data", ref.uuid.toString());
        out.put("player", ref.model());
        out.put("scope", spec.id);
        out.put("online", online);
        out.put("revision", revisionOf(slots));
        out.put("slots", slots);
        out.put("items", items(slots, spec));
        return out;
    }

    /** 把槽位写回玩家 data 文件；保留不在本次编辑范围的其他槽位。 */
    private void applyOfflineToTag(CompoundTag tag, SlotSpec spec, List<String> slots) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        if (spec == SlotSpec.ENDER) {
            ListTag list = new ListTag();
            for (int i = 0; i < spec.size; i++) {
                ItemStack stack = decodeSlot(slots.get(i));
                if (stack.isEmpty()) continue;
                CompoundTag entry = encodeSlot(ops, stack);
                if (entry == null) continue;
                entry.putByte("Slot", (byte) i);
                list.add(entry);
            }
            tag.put("EnderItems", list);
            return;
        }
        ListTag old = tag.getListOrEmpty("Inventory");
        ListTag list = new ListTag();
        int min = spec.base;
        int max = spec.base + spec.size;
        for (int i = 0; i < old.size(); i++) {
            CompoundTag entry = old.getCompoundOrEmpty(i);
            int slot = entry.getByteOr("Slot", (byte) -1);
            if (slot >= min && slot < max) continue;
            list.add(entry.copy());
        }
        for (int i = 0; i < spec.size; i++) {
            ItemStack stack = decodeSlot(slots.get(i));
            if (stack.isEmpty()) continue;
            CompoundTag entry = encodeSlot(ops, stack);
            if (entry == null) continue;
            entry.putByte("Slot", (byte) (spec.base + i));
            list.add(entry);
        }
        tag.put("Inventory", list);
    }

    private ItemStack decodeSlot(String encoded) {
        return encoded == null || encoded.isBlank()
                ? ItemStack.EMPTY : LotteryItemCodec.decode(encoded, registries);
    }

    private CompoundTag encodeSlot(RegistryOps<Tag> ops, ItemStack stack) {
        Tag encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
        if (encoded instanceof CompoundTag compound) return compound.copy();
        return null;
    }

    private void applyPending(ServerPlayer player, boolean stopping) {
        for (SlotSpec spec : SlotSpec.values()) {
            Path queue = queueFile(player.getUUID(), spec);
            if (queue == null || !Files.isRegularFile(queue)) continue;
            Map<String, Object> request;
            List<String> slots;
            try {
                request = parse(Files.readString(queue, StandardCharsets.UTF_8));
                slots = request == null ? null : slots(request.get("slots"));
            } catch (IOException | RuntimeException e) {
                Samsara.LOGGER.warn("[inventory] 读取待写入队列失败 {}: {}", queue, e.getMessage());
                continue;
            }
            if (slots == null || slots.size() != spec.size) {
                Samsara.LOGGER.warn("[inventory] 待写入队列槽位数无效 {}，保留文件", queue);
                continue;
            }
            String validation = validateSlots(slots, spec);
            if (validation != null) {
                Samsara.LOGGER.warn("[inventory] 待写入队列校验失败 {}: {}，保留文件",
                        queue, validation);
                continue;
            }
            int expected = intValue(request.get("dataVersion"), 0);
            int actual = serverDataVersion();
            if (expected > 0 && actual > 0 && expected != actual) {
                Samsara.LOGGER.warn("[inventory] 待写入队列 DataVersion 不匹配 {}: {} != {}，保留文件",
                        queue, expected, actual);
                continue;
            }
            List<String> before = encodeOnline(player, spec);
            WebAuditService.get().captureInventory("/api/inventory/queued", "system",
                    player.getGameProfile().name() + " " + spec.id
                            + (stopping ? " 停服前应用" : " 登录时应用"),
                    GSON.toJson(snapshot(new PlayerRef(player.getUUID(),
                            player.getGameProfile().name(), player), spec, before)));
            try {
                applyOnline(player, spec, slots);
                Files.deleteIfExists(queue);
                Samsara.LOGGER.info("[inventory] 已应用待写入队列：{} {}",
                        player.getGameProfile().name(), spec.id);
            } catch (IOException | RuntimeException e) {
                Samsara.LOGGER.warn("[inventory] 应用待写入队列失败 {}: {}，保留文件",
                        queue, e.getMessage());
            }
        }
    }

    private List<String> encodeOnline(ServerPlayer player, SlotSpec spec) {
        List<String> out = new ArrayList<>(spec.size);
        for (int i = 0; i < spec.size; i++) {
            ItemStack stack = spec == SlotSpec.ENDER
                    ? player.getEnderChestInventory().getItem(i)
                    : player.getInventory().getItem(spec.base + i);
            out.add(LotteryItemCodec.encode(stack, registries));
        }
        return out;
    }

    private void applyOnline(ServerPlayer player, SlotSpec spec, List<String> slots) {
        for (int i = 0; i < spec.size; i++) {
            String encoded = slots.get(i);
            ItemStack stack = encoded == null || encoded.isBlank()
                    ? ItemStack.EMPTY
                    : LotteryItemCodec.decode(encoded, registries);
            if (spec == SlotSpec.ENDER) {
                player.getEnderChestInventory().setItem(i, stack);
            } else {
                player.getInventory().setItem(spec.base + i, stack);
            }
        }
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != null) player.containerMenu.broadcastChanges();
        if (spec == SlotSpec.ENDER) player.getEnderChestInventory().setChanged();
    }

    private List<String> decodeOffline(CompoundTag tag, SlotSpec spec) {
        return decodeOfflineSlots(tag, spec, registries);
    }

    static List<String> decodeOfflineSlots(CompoundTag tag, SlotSpec spec,
                                           RegistryAccess registries) {
        List<String> slots = new ArrayList<>(spec.size);
        for (int i = 0; i < spec.size; i++) slots.add("");
        ListTag list = spec == SlotSpec.ENDER
                ? tag.getListOrEmpty("EnderItems")
                : tag.getListOrEmpty("Inventory");
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            int slot = entry.getByteOr("Slot", (byte) -1);
            int index = slot - spec.base;
            if (index < 0 || index >= spec.size) continue;
            ItemStack stack = ItemStack.CODEC.parse(ops, entry).result().orElse(ItemStack.EMPTY);
            if (!stack.isEmpty()) slots.set(index, LotteryItemCodec.encode(stack, registries));
        }
        return slots;
    }

    private Map<String, Object> snapshot(PlayerRef ref, SlotSpec spec, List<String> slots) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("player", ref.model());
        out.put("scope", spec.id);
        out.put("capturedAt", System.currentTimeMillis());
        out.put("slots", slots);
        out.put("revision", revisionOf(slots));
        return out;
    }

    private List<Map<String, Object>> items(List<String> encodedSlots, SlotSpec spec) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < encodedSlots.size(); i++) {
            String encoded = encodedSlots.get(i);
            if (encoded == null || encoded.isBlank()) continue;
            ItemStack stack = LotteryItemCodec.decode(encoded, registries);
            if (stack.isEmpty()) continue;
            String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            Map<String, Object> item = new LinkedHashMap<>(
                    com.shenqianye.samsara.item.ItemStackModel.compact(stack, encoded));
            item.put("slot", i);
            item.put("absoluteSlot", spec.base + i);
            item.put("id", id);
            out.add(item);
        }
        return out;
    }

    static long revisionOf(List<String> slots) {
        long hash = 0xcbf29ce484222325L;
        for (String slot : slots) {
            String value = slot == null ? "" : slot;
            for (int i = 0; i < value.length(); i++) {
                hash ^= value.charAt(i);
                hash *= 0x100000001b3L;
            }
            hash ^= 0x1f;
            hash *= 0x100000001b3L;
        }
        return hash & 0xFFFFFFFFFFFFFL;
    }

    private String validateSlots(List<String> slots, SlotSpec spec) {
        return validateEncodedSlots(slots, spec, registries);
    }

    static String validateEncodedSlots(List<String> slots, SlotSpec spec,
                                       RegistryAccess registries) {
        if (slots.size() != spec.size) return "槽位数量必须是 " + spec.size;
        for (int i = 0; i < slots.size(); i++) {
            String encoded = slots.get(i);
            if (encoded == null || encoded.isBlank()) continue;
            ItemStack stack;
            try {
                stack = LotteryItemCodec.decode(encoded, registries);
            } catch (RuntimeException e) {
                return "槽位 " + i + " 编码无法解析";
            }
            if (stack.isEmpty()) return "槽位 " + i + " 不是有效物品";
            if (stack.getCount() < 1 || stack.getCount() > stack.getMaxStackSize()) {
                return "槽位 " + i + " 数量必须在 1.." + stack.getMaxStackSize();
            }
        }
        return null;
    }

    private PlayerRef resolve(String ref) {
        if (server == null) return null;
        UUID uuid = parseUuid(ref);
        ServerPlayer online = null;
        if (uuid != null) {
            online = server.getPlayerList().getPlayer(uuid);
            if (online == null) {
                // data UUID / 组内任一账号 UUID 也要能定位到当前在线玩家。
                com.shenqianye.samsara.identity.IdentityService identity =
                        com.shenqianye.samsara.identity.IdentityService.getInstance();
                if (identity != null) online = identity.onlineFor(uuid);
            }
        } else {
            online = server.getPlayerList().getPlayerByName(ref);
            if (online != null) uuid = online.getUUID();
        }
        if (online != null) {
            return new PlayerRef(online.getUUID(), online.getGameProfile().name(), online);
        }
        String name = "";
        if (uuid == null) {
            for (PlayerRegistry.RegPlayer row : PlayerRegistry.getInstance().read(server)) {
                if (row.name.equalsIgnoreCase(ref)) {
                    uuid = row.uuid;
                    name = row.name;
                    break;
                }
            }
        } else {
            for (PlayerRegistry.RegPlayer row : PlayerRegistry.getInstance().read(server)) {
                if (row.uuid.equals(uuid)) {
                    name = row.name;
                    break;
                }
            }
        }
        return uuid == null ? null : new PlayerRef(uuid, name, null);
    }

    private Path playerFile(UUID uuid) {
        return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat");
    }

    /** 身份组的根数据在 identity/playerdata；账号 UUID 和 data UUID 都能解析到同一份。 */
    private IdentityTarget identityTarget(UUID uuid) {
        if (uuid == null) return null;
        com.shenqianye.samsara.identity.IdentityService identity =
                com.shenqianye.samsara.identity.IdentityService.getInstance();
        if (identity == null) return null;
        com.shenqianye.samsara.identity.IdentityConfig.Group group = identity.groupOf(uuid);
        if (group == null) return null;
        UUID data = group.dataUuid();
        if (data == null) return null;
        com.shenqianye.samsara.identity.IdentityDataSync sync = identity.dataSync();
        if (sync == null) return null;
        Path file = sync.identityData(data);
        return Files.isRegularFile(file) ? new IdentityTarget(data, file) : null;
    }

    private record IdentityTarget(UUID data, Path file) {
    }

    private Path queueFile(UUID uuid, SlotSpec spec) {
        return pendingDir == null ? null : pendingDir.resolve(uuid + "-" + spec.id + ".json");
    }

    private CompoundTag readTag(Path file) throws IOException {
        return NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_BYTES));
    }

    private int serverDataVersion() {
        return net.minecraft.SharedConstants.WORLD_VERSION;
    }

    private List<String> readQueue(Path queue, SlotSpec spec) {
        return readQueueFile(queue, spec);
    }

    static List<String> readQueueFile(Path queue, SlotSpec spec) {
        if (queue == null || !Files.isRegularFile(queue)) return null;
        try {
            Map<String, Object> model = parse(Files.readString(queue, StandardCharsets.UTF_8));
            List<String> slots = model == null ? null : slots(model.get("slots"));
            if (slots == null || slots.size() != spec.size) return null;
            return slots;
        } catch (IOException | RuntimeException e) {
            Samsara.LOGGER.warn("[inventory] 读取待写入队列失败 {}: {}", queue, e.getMessage());
            return null;
        }
    }

    private boolean writeQueue(Path queue, Map<String, Object> model) {
        return writeQueueFile(queue, GSON.toJson(model));
    }

    static boolean writeQueueFile(Path queue, String json) {
        if (queue == null) return false;
        Path temp = queue.resolveSibling(queue.getFileName() + ".tmp");
        try {
            Files.createDirectories(queue.getParent());
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, queue, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomic) {
                Files.move(temp, queue, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            Samsara.LOGGER.warn("[inventory] 写入待处理队列失败 {}: {}", queue, e.getMessage());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // ignore
            }
            return false;
        }
    }

    private Map<String, Object> onServer(java.util.function.Supplier<Map<String, Object>> action) {
        if (server == null) return error(503, "服务器未启动");
        try {
            if (server.isSameThread()) return action.get();
            java.util.concurrent.CompletableFuture<Map<String, Object>> future =
                    new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    future.complete(action.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future.get(12L, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return error(500, "背包操作失败：" + e.getMessage());
        }
    }

    private static Map<String, Object> parse(String body) {
        try {
            Map<String, Object> parsed = GSON.fromJson(body, MAP_TYPE);
            return parsed == null ? null : parsed;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> slots(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) out.add(item == null ? "" : String.valueOf(item));
        return out;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static UUID parseUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> error(int status, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("status", status);
        out.put("error", message);
        return out;
    }

    enum SlotSpec {
        INVENTORY("inventory", 0, 41),
        ENDER("ender", 0, 27),
        ARMOR("armor", 36, 4);

        final String id;
        final int base;
        final int size;

        SlotSpec(String id, int base, int size) {
            this.id = id;
            this.base = base;
            this.size = size;
        }

        static SlotSpec from(String value) {
            if (value == null || value.isBlank()) return null;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "inventory", "inv" -> INVENTORY;
                case "ender", "enderchest", "ender_chest" -> ENDER;
                case "armor", "armour" -> ARMOR;
                default -> null;
            };
        }
    }

    record PlayerRef(UUID uuid, String name, ServerPlayer online) {
        Map<String, Object> model() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("uuid", uuid.toString());
            out.put("name", name == null ? "" : name);
            out.put("online", online != null);
            return out;
        }
    }
}
