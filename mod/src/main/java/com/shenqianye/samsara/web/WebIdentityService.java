package com.shenqianye.samsara.web;

import com.shenqianye.samsara.identity.IdentityAudit;
import com.shenqianye.samsara.identity.IdentityConfig;
import com.shenqianye.samsara.identity.IdentityConfigStore;
import com.shenqianye.samsara.identity.IdentityDataSync;
import com.shenqianye.samsara.identity.IdentityJournal;
import com.shenqianye.samsara.identity.IdentityPreview;
import com.shenqianye.samsara.identity.IdentityService;
import com.shenqianye.samsara.identity.IdentityStateStore;
import com.shenqianye.samsara.identity.IdentityValidator;
import com.shenqianye.samsara.season.PlayerRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 生产环境身份组 Web 服务。 */
public final class WebIdentityService implements IdentityWebService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    /** UUID → 名称缓存；网络不可用时也缓存空结果，避免每次请求都卡住。 */
    private static final Map<String, CachedName> NAME_CACHE = new ConcurrentHashMap<>();
    private static final long NAME_CACHE_MS = 10 * 60_000L;
    private static final int MAX_REMOTE_LOOKUPS = 6;

    @Override
    public Map<String, Object> model() {
        IdentityService service = IdentityService.getInstance();
        if (service.configStore() == null) {
            service.initConfig();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("config", IdentityConfigStore.toModel(service.config()));
        out.put("path", service.configStore().file().toString());
        out.put("identityRoot", service.identityRoot() == null ? ""
                : service.identityRoot().toString());
        out.put("errors", errorMap(service.configStore().issues()));
        out.put("issues", issues(service.configStore().issues()));
        out.put("states", states(service.stateStore()));
        out.put("candidates", candidates(service));
        return out;
    }

    @Override
    public Map<String, Object> save(Map<String, Object> body) {
        IdentityService service = IdentityService.getInstance();
        if (service.configStore() == null) {
            service.initConfig();
        }
        IdentityConfig next;
        try {
            next = fromMap(body);
        } catch (RuntimeException e) {
            return fail("配置格式不正确：" + e.getMessage());
        }
        Map<String, String> dataChangeErrors = new LinkedHashMap<>();
        for (int i = 0; i < next.groups.size(); i++) {
            IdentityConfig.Group candidate = next.groups.get(i);
            if (candidate == null) {
                continue;
            }
            IdentityConfig.Group existing = service.groupByName(candidate.name);
            if (existing != null && !String.valueOf(candidate.data)
                    .equals(String.valueOf(existing.data))) {
                dataChangeErrors.put("groups[" + i + "].data",
                        "修改 data UUID 必须走“重绑数据存储”流程，不能直接保存");
            }
        }
        if (!dataChangeErrors.isEmpty()) {
            Map<String, Object> out = fail("data UUID 修改被拒绝");
            out.put("errors", dataChangeErrors);
            return out;
        }
        IdentityConfigStore.SaveResult result = service.configStore().save(next);
        if (!result.ok()) {
            Map<String, Object> out = fail("身份组配置校验失败");
            out.put("errors", result.errors());
            out.put("issues", issues(result.issues()));
            return out;
        }
        service.applySavedConfig(next);
        IdentityAudit.log(service.identityRoot(), "web_save", "web", null,
                "groups=" + next.groups.size() + " enabled=" + next.enabled);
        Map<String, Object> out = model();
        out.put("ok", true);
        out.put("message", "身份组配置已保存并热重载");
        return out;
    }

    @Override
    public Map<String, Object> preview(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        UUID data = uuid(body.get("data"));
        String groupName = text(body.get("group"));
        IdentityService service = IdentityService.getInstance();
        IdentityConfig.Group group = groupName == null ? null : service.groupByName(groupName);
        if (group != null && data == null) {
            data = group.dataUuid();
        }
        List<UUID> aliases = new ArrayList<>();
        if (body.get("uuids") instanceof List<?> list) {
            for (Object item : list) {
                UUID uuid = uuid(item);
                if (uuid != null) {
                    aliases.add(uuid);
                }
            }
        }
        if (group != null) {
            for (UUID account : group.accountUuids()) {
                if (!aliases.contains(account)) {
                    aliases.add(account);
                }
            }
        }
        if (aliases.isEmpty() && data != null) {
            aliases.add(data);
            aliases.addAll(service.aliasesOf(data));
        }
        out.put("data", data == null ? null : data.toString());
        out.put("aliases", aliases.stream().map(UUID::toString).toList());
        out.put("claims", IdentityPreview.claimCount(data == null ? null : service.identityOf(data)));
        out.put("playerdata", data != null && service.dataSync() != null
                && service.dataSync().dataExists(data));
        out.put("tracks", IdentityPreview.trackCount(aliases));
        out.put("activity", IdentityPreview.activityCount(aliases));
        out.put("online", IdentityPreview.online(service.server(), data));
        out.put("text", IdentityPreview.describe(service.server(), service.identityRoot(),
                data, aliases));
        return out;
    }

    @Override
    public Map<String, Object> rebind(Map<String, Object> body) {
        IdentityService service = IdentityService.getInstance();
        if (service.configStore() == null || service.dataSync() == null) {
            return fail("身份服务尚未初始化");
        }
        String groupName = text(body.get("group"));
        IdentityConfig.Group group = groupName == null ? null : service.groupByName(groupName);
        if (group == null) {
            return fail("找不到身份组：" + groupName);
        }
        UUID oldData = group.dataUuid();
        UUID newData = uuid(body.get("newData"));
        if (oldData == null || newData == null) {
            return fail("旧/新 data UUID 无效");
        }
        if (oldData.equals(newData)) {
            return fail("新旧 data UUID 相同，无需重绑");
        }
        IdentityConfig.Group owner = service.groupOf(newData);
        if (owner != null && owner != group) {
            return fail("新 data UUID 已被其他身份组使用");
        }
        if (service.isOnlineInGroup(group)) {
            return fail("该身份组当前有账号在线，禁止重绑 data UUID");
        }
        boolean confirm = bool(body.get("confirm"));
        if (!confirm) {
            return fail("重绑必须二次确认（confirm=true）");
        }
        String mode = text(body.get("mode"));
        if (mode == null || mode.isBlank()) {
            mode = "reject";
        }
        if (!List.of("reject", "overwrite", "merge").contains(mode)) {
            return fail("mode 只能是 reject / overwrite / merge");
        }
        IdentityDataSync sync = service.dataSync();
        boolean targetExists = sync.dataExists(newData);
        if (targetExists && "reject".equals(mode)) {
            return fail("新 data 已有玩家数据；如需继续请显式选择 overwrite 或 merge（会先备份）");
        }
        if ("merge".equals(mode) && targetExists) {
            // 玩家数据不能逐字段合并：merge = 保留新数据，仅补齐缺失文件；旧数据完整备份。
        }
        Path archive = service.identityRoot().resolve("archive")
                .resolve("rebind-" + LocalDateTime.now().format(TS));
        try {
            IdentityDataSync.backup(sync.identityData(oldData), archive, "old-data");
            IdentityDataSync.backup(sync.identityAdvancements(oldData), archive, "old-advancements");
            IdentityDataSync.backup(sync.identityStats(oldData), archive, "old-stats");
            IdentityDataSync.backup(sync.identityData(newData), archive, "new-data");
            IdentityDataSync.backup(sync.identityAdvancements(newData), archive, "new-advancements");
            IdentityDataSync.backup(sync.identityStats(newData), archive, "new-stats");
            copyData(sync, oldData, newData, "overwrite".equals(mode) || !targetExists);
        } catch (RuntimeException e) {
            return fail("重绑备份/复制失败（源数据未删除）：" + e.getMessage());
        }

        IdentityConfig next = service.config().copy();
        boolean changed = false;
        for (IdentityConfig.Group candidate : next.groups) {
            if (candidate != null && candidate.name != null
                    && candidate.name.equalsIgnoreCase(group.name)) {
                candidate.data = newData.toString();
                changed = true;
                break;
            }
        }
        if (!changed) {
            return fail("重绑失败：配置副本中找不到该组");
        }
        IdentityConfigStore.SaveResult saved = service.configStore().save(next);
        if (!saved.ok()) {
            return fail("重绑失败：identity.yml 未写入（数据副本与备份保留）：" + saved.errors());
        }
        service.applySavedConfig(next);
        IdentityStateStore states = service.stateStore();
        if (states != null) {
            IdentityStateStore.GroupState oldState = states.get(oldData);
            IdentityStateStore.GroupState copy = oldState == null
                    ? new IdentityStateStore.GroupState() : oldState.copy();
            copy.dataUuid = newData.toString();
            states.remove(oldData);
            states.put(newData, copy);
            states.save();
        }
        IdentityAudit.log(service.identityRoot(), "rebind", "web", newData,
                "group=" + group.name + " from=" + oldData + " to=" + newData
                        + " mode=" + mode + " archive=" + archive);
        Map<String, Object> out = model();
        out.put("ok", true);
        out.put("message", "data UUID 已重绑：" + oldData + " → " + newData
                + "（旧数据与备份保留）");
        out.put("archive", archive.toString());
        return out;
    }

    @Override
    public Map<String, Object> reload() {
        IdentityService service = IdentityService.getInstance();
        String message = service.reload();
        Map<String, Object> out = model();
        out.put("ok", !message.contains("失败"));
        out.put("message", message);
        return out;
    }

    @Override
    public Map<String, Object> audit() {
        Map<String, Object> out = new LinkedHashMap<>();
        IdentityService service = IdentityService.getInstance();
        out.put("ok", true);
        out.put("text", service.auditReport());
        out.put("issues", issues(service.configStore() == null ? List.of()
                : service.configStore().issues()));
        out.put("states", states(service.stateStore()));
        return out;
    }

    @Override
    public Map<String, Object> generate() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("data", UUID.randomUUID().toString());
        return out;
    }

    @Override
    public Map<String, Object> resolve(Map<String, Object> body) {
        IdentityService service = IdentityService.getInstance();
        Map<UUID, String> localNames = new LinkedHashMap<>();
        Set<UUID> online = new HashSet<>();
        if (service.server() != null) {
            for (PlayerRegistry.RegPlayer player : PlayerRegistry.getInstance()
                    .read(service.server())) {
                localNames.putIfAbsent(player.uuid, player.name);
                if (service.server().getPlayerList().getPlayer(player.uuid) != null) {
                    online.add(player.uuid);
                }
            }
        }

        String queryName = text(body.get("name"));
        UUID premium = null;
        UUID offline = null;
        String premiumNote = "";
        if (!queryName.isBlank()) {
            offline = PlayerRegistry.offlineUuid(queryName);
            localNames.putIfAbsent(offline, queryName);
            premium = lookupPremiumByName(queryName);
            if (premium != null) {
                localNames.putIfAbsent(premium, queryName);
            } else {
                premiumNote = "未能从 Mojang 获取正版 UUID（可能离线或名称不存在）；"
                        + "离线 UUID 仍可正常计算";
            }
        }

        List<UUID> uuids = new ArrayList<>();
        if (body.get("uuids") instanceof List<?> list) {
            for (Object item : list) {
                UUID uuid = uuid(item);
                if (uuid != null) {
                    uuids.add(uuid);
                }
            }
        }
        if (premium != null) {
            uuids.add(premium);
        }
        if (offline != null) {
            uuids.add(offline);
        }

        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Boolean> onlineMap = new LinkedHashMap<>();
        int remoteLookups = 0;
        for (UUID uuid : uuids) {
            String name = localNames.get(uuid);
            if (name == null && uuid.version() == 4 && remoteLookups < MAX_REMOTE_LOOKUPS) {
                name = lookupNameByUuid(uuid);
                remoteLookups++;
            }
            if (name != null && !name.isBlank()) {
                names.put(uuid.toString(), name);
            }
            onlineMap.put(uuid.toString(), online.contains(uuid));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("name", queryName);
        out.put("premium", premium == null ? null : premium.toString());
        out.put("offline", offline == null ? null : offline.toString());
        out.put("premiumNote", premiumNote);
        out.put("names", names);
        out.put("online", onlineMap);
        return out;
    }

    // ---- 名称解析 ----

    private static UUID lookupPremiumByName(String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "https://api.mojang.com/users/profiles/minecraft/" + encoded))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return null;
            }
            JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
            String id = object.has("id") ? object.get("id").getAsString() : "";
            if (id.isBlank()) {
                return null;
            }
            String dashed = id.replaceFirst(
                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
            UUID uuid = UUID.fromString(dashed);
            NAME_CACHE.put(uuid.toString(), new CachedName(name, System.currentTimeMillis()));
            return uuid;
        } catch (Exception e) {
            return null;
        }
    }

    private static String lookupNameByUuid(UUID uuid) {
        String key = uuid.toString();
        CachedName cached = NAME_CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at < NAME_CACHE_MS) {
            return cached.name;
        }
        try {
            String id = key.replace("-", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "https://sessionserver.mojang.com/session/minecraft/profile/" + id))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
                if (object.has("name")) {
                    String name = object.get("name").getAsString();
                    NAME_CACHE.put(key, new CachedName(name, System.currentTimeMillis()));
                    return name;
                }
            }
        } catch (Exception ignored) {
            // 网络不可用时静默降级；前端仍会显示 UUID 短码。
        }
        NAME_CACHE.put(key, new CachedName(null, System.currentTimeMillis()));
        return null;
    }

    private record CachedName(String name, long at) {
    }

    // ---- 工具 ----

    private static void copyData(IdentityDataSync sync, UUID oldData, UUID newData,
                                 boolean overwrite) {
        copyFile(sync.identityData(oldData), sync.identityData(newData), overwrite);
        copyFile(sync.identityAdvancements(oldData), sync.identityAdvancements(newData), overwrite);
        copyFile(sync.identityStats(oldData), sync.identityStats(newData), overwrite);
    }

    private static void copyFile(Path source, Path target, boolean overwrite) {
        if (!Files.isRegularFile(source)) {
            return;
        }
        if (Files.isRegularFile(target) && !overwrite) {
            return;
        }
        try {
            IdentityJournal.copyAtomic(source, target,
                    target.resolveSibling(target.getFileName() + ".bak"));
        } catch (RuntimeException e) {
            throw new RuntimeException(target.getFileName() + "：" + e.getMessage(), e);
        }
    }

    private static Map<String, Object> states(IdentityStateStore store) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (store == null) {
            return out;
        }
        for (Map.Entry<String, IdentityStateStore.GroupState> entry : store.all().entrySet()) {
            IdentityStateStore.GroupState state = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dataUuid", state.dataUuid);
            item.put("lastWriter", state.lastWriter);
            item.put("lastSyncAt", state.lastSyncAt);
            item.put("syncBlocked", state.syncBlocked);
            out.put(entry.getKey(), item);
        }
        return out;
    }

    private static List<Map<String, Object>> candidates(IdentityService service) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (service.server() == null) {
            return out;
        }
        for (PlayerRegistry.RegPlayer player : PlayerRegistry.getInstance()
                .read(service.server())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", player.name);
            item.put("uuid", player.uuid.toString());
            item.put("online", service.server().getPlayerList().getPlayer(player.uuid) != null);
            item.put("type", player.uuid.equals(PlayerRegistry.offlineUuid(player.name))
                    ? "offline" : "premium");
            out.add(item);
        }
        return out;
    }

    private static List<Map<String, Object>> issues(List<IdentityValidator.Issue> issues) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IdentityValidator.Issue issue : issues) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", issue.field());
            item.put("message", issue.message());
            out.add(item);
        }
        return out;
    }

    private static Map<String, String> errorMap(List<IdentityValidator.Issue> issues) {
        Map<String, String> out = new LinkedHashMap<>();
        for (IdentityValidator.Issue issue : issues) {
            out.putIfAbsent(issue.field(), issue.message());
        }
        return out;
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }

    private static IdentityConfig fromMap(Map<String, Object> body) {
        IdentityConfig config = new IdentityConfig();
        Object identity = body.get("identity");
        if (identity instanceof Map<?, ?> map) {
            config.enabled = bool(map.get("enabled"));
            config.syncPlayerData = map.containsKey("syncPlayerData")
                    ? bool(map.get("syncPlayerData")) : true;
        } else {
            config.enabled = bool(body.get("enabled"));
            config.syncPlayerData = body.containsKey("syncPlayerData")
                    ? bool(body.get("syncPlayerData")) : true;
        }
        Object pets = body.get("pets");
        if (pets instanceof Map<?, ?> map) {
            config.maxOwners = integer(map.get("maxOwners"), IdentityConfig.DEFAULT_MAX_OWNERS);
        } else {
            config.maxOwners = integer(body.get("maxOwners"), IdentityConfig.DEFAULT_MAX_OWNERS);
        }
        Object groups = body.get("groups");
        if (groups instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    config.groups.add(null);
                    continue;
                }
                IdentityConfig.Group group = new IdentityConfig.Group();
                group.name = text(map.get("name"));
                group.data = text(map.get("data"));
                group.premium = nullable(map.get("premium"));
                group.offline = nullable(map.get("offline"));
                group.note = text(map.get("note"));
                config.groups.add(group);
            }
        }
        return config;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String nullable(Object value) {
        String text = text(value);
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static UUID uuid(Object value) {
        String text = text(value);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
