package com.shenqianye.samsara.web;

import com.shenqianye.samsara.season.StackLayer;
import com.shenqianye.samsara.season.StackManifest;
import com.shenqianye.samsara.season.StackTeleport;
import com.shenqianye.samsara.season.WorldStackManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户端只读 Web 服务。
 *
 * <p>不要求 token，也不接触配置/备份/权限/文件路径。只返回玩家可见的
 * 地图、世界栈、在线玩家与服务器概况；字段经过白名单重建，不能直接把
 * 管理端 {@link WebMapService#model()} 的原始 Map 透传出去。
 *
 * <p>本类与服务端只共享 {@link WebMapService} 的读取逻辑与 tile 缓存；
 * 用户端没有任何写接口。
 */
public final class WebUserService {
    private final WebMapService mapService;
    private final MinecraftServer server;
    private WorldStackIconService icons;
    private MojangSkinService skins;

    private WorldStackIconService icons() {
        if (icons == null) icons = new WorldStackIconService();
        return icons;
    }

    private MojangSkinService skins() {
        if (skins == null) skins = new MojangSkinService();
        return skins;
    }

    public WebUserService(WebMapService mapService, MinecraftServer server) {
        this.mapService = mapService;
        this.server = server;
    }

    /** 服务器概况：只保留公开字段。 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("scope", "user");
        out.put("generatedAt", System.currentTimeMillis());
        if (server != null) {
            out.put("online", server.getPlayerList().getPlayerCount());
            out.put("maxPlayers", server.getPlayerList().getMaxPlayers());
            out.put("version", server.getServerVersion());
            out.put("motd", server.getMotd());
            out.put("tickCount", server.getTickCount());
        } else {
            out.put("online", 0);
            out.put("maxPlayers", 0);
            out.put("version", "");
            out.put("motd", "");
            out.put("tickCount", 0);
        }
        return out;
    }

    /** 世界栈公开列表：栈层 id/顺序/种子/在线人数，不含路径与备份。 */
    public Map<String, Object> worlds() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("scope", "user");
        out.put("generatedAt", System.currentTimeMillis());
        if (server == null) {
            out.put("topStackId", "world");
            out.put("stacks", List.of());
            return out;
        }
        StackManifest manifest = WorldStackManager.getInstance().manifest(server);
        List<Map<String, Object>> stacks = new ArrayList<>();
        for (StackLayer layer : manifest.layers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", layer.id);
            item.put("index", layer.index);
            item.put("top", layer.top);
            item.put("label", layer.top ? "world（栈顶）" : layer.id);
            item.put("seed", layer.seed);
            item.put("online", onlineIn(layer));
            item.put("spawn", Map.of("x", layer.spawnX, "y", layer.spawnY, "z", layer.spawnZ));
            item.put("icon", icons().describe(layer.id));
            stacks.add(item);
        }
        out.put("topStackId", manifest.top() == null ? "world" : manifest.top().id);
        out.put("stacks", stacks);
        return out;
    }

    /** 地图模型：重建白名单字段，去掉 worldRoot/cache/metaPath/owner 等管理信息。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> mapModel() {
        Map<String, Object> raw = mapService.model();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", raw.get("ok"));
        out.put("scope", "user");
        out.put("generatedAt", raw.get("generatedAt"));
        out.put("claimRange", raw.get("claimRange"));
        out.put("topStackId", raw.get("topStackId"));
        List<Map<String, Object>> stacks = new ArrayList<>();
        Object rawStacks = raw.get("stacks");
        if (rawStacks instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> source)) {
                    continue;
                }
                StackView view = new StackView((Map<String, Object>) source);
                stacks.add(view.toMap());
            }
        }
        out.put("stacks", stacks);
        return out;
    }

    /** 在线玩家：地图只需要名字/栈层/维度/坐标，不返回 UUID、生命值、朝向。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> players() {
        Map<String, Object> raw = mapService.players();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("scope", "user");
        out.put("generatedAt", raw.get("generatedAt"));
        List<Map<String, Object>> players = new ArrayList<>();
        Object rawPlayers = raw.get("players");
        if (rawPlayers instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> source)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", source.get("name"));
                item.put("stackId", source.get("stackId"));
                item.put("dimension", source.get("dimension"));
                item.put("dimensionId", source.get("dimensionId"));
                item.put("x", source.get("x"));
                item.put("y", source.get("y"));
                item.put("z", source.get("z"));
                players.add(item);
            }
        }
        out.put("players", players);
        return out;
    }

    public byte[] tile(String stackId, String dimension, int regionX, int regionZ,
                       int scale, boolean shading) {
        return mapService.tile(stackId, dimension, regionX, regionZ, scale, shading);
    }

    /** 实时层白名单：保留轨迹/操作，但去掉 UUID、生命值、朝向等管理字段。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> live() {
        Map<String, Object> raw = mapService.live();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("generatedAt", raw.get("generatedAt"));
        out.put("truncated", raw.get("truncated"));
        List<Map<String, Object>> players = new ArrayList<>();
        Object rawPlayers = raw.get("players");
        if (rawPlayers instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> source)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("kind", "player");
                item.put("name", source.get("name"));
                item.put("type", "minecraft:player");
                item.put("stackId", source.get("stackId"));
                item.put("dimension", source.get("dimension"));
                item.put("dimensionId", source.get("dimensionId"));
                item.put("x", source.get("x"));
                item.put("y", source.get("y"));
                item.put("z", source.get("z"));
                item.put("trail", source.get("trail"));
                players.add(item);
            }
        }
        out.put("players", players);
        List<Map<String, Object>> entities = new ArrayList<>();
        Object rawEntities = raw.get("entities");
        if (rawEntities instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> source)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("kind", "entity");
                item.put("name", source.get("name"));
                item.put("type", source.get("type"));
                item.put("category", source.get("category"));
                item.put("stackId", source.get("stackId"));
                item.put("dimension", source.get("dimension"));
                item.put("dimensionId", source.get("dimensionId"));
                item.put("x", source.get("x"));
                item.put("y", source.get("y"));
                item.put("z", source.get("z"));
                item.put("trail", source.get("trail"));
                entities.add(item);
            }
        }
        out.put("entities", entities);
        List<Map<String, Object>> operations = new ArrayList<>();
        Object rawOperations = raw.get("operations");
        if (rawOperations instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> source)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("timeMs", source.get("timeMs"));
                item.put("actor", source.get("actor"));
                item.put("stackId", source.get("stackId"));
                item.put("dimension", source.get("dimension"));
                item.put("x", source.get("x"));
                item.put("y", source.get("y"));
                item.put("z", source.get("z"));
                item.put("action", source.get("action"));
                item.put("before", source.get("before"));
                item.put("after", source.get("after"));
                operations.add(item);
            }
        }
        out.put("operations", operations);
        return out;
    }

    /** 公开只读图标字节；只有图片本身，不返回路径或上传元数据。 */
    public WorldStackIconService.IconFile icon(String stackId) {
        return icons().read(stackId);
    }

    /** 公开头像代理：Mojang 官方皮肤，失败时返回 null 让前端用本地 SVG。 */
    public MojangSkinService.Skin avatar(String uuid, String name) {
        return skins().skin(uuid, name);
    }

    private int onlineIn(StackLayer layer) {
        if (server == null || layer == null) {
            return 0;
        }
        int count = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            StackLayer current = StackTeleport.layerOf(server, player);
            if (current != null && layer.id.equals(current.id)) {
                count++;
            }
        }
        return count;
    }

    /** 单个栈层的白名单视图。 */
    private static final class StackView {
        private final Map<String, Object> source;

        private StackView(Map<String, Object> source) {
            this.source = source;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", source.get("id"));
            out.put("index", source.get("index"));
            out.put("top", source.get("top"));
            out.put("label", source.get("label"));
            out.put("createdAt", source.get("createdAt"));
            out.put("seed", source.get("seed"));
            out.put("spawn", source.get("spawn"));
            out.put("levelType", source.get("levelType"));
            out.put("dimensions", dimensions(source.get("dimensions")));
            out.put("spawnHistory", spawnHistory(source.get("spawnHistory")));
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dimensions(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> source)) {
                continue;
            }
            Map<String, Object> dim = new LinkedHashMap<>();
            dim.put("id", source.get("id"));
            dim.put("dim", source.get("dim"));
            dim.put("name", source.get("name"));
            dim.put("regions", source.get("regions"));
            dim.put("protectedChunks", source.get("protectedChunks"));
            dim.put("banners", banners(source.get("banners")));
            dim.put("protectedCount", source.get("protectedCount"));
            dim.put("bannerCount", source.get("bannerCount"));
            dim.put("bounds", source.get("bounds"));
            out.add(dim);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> banners(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> source)) {
                continue;
            }
            Map<String, Object> banner = new LinkedHashMap<>();
            banner.put("x", source.get("x"));
            banner.put("y", source.get("y"));
            banner.put("z", source.get("z"));
            banner.put("facing", source.get("facing"));
            banner.put("chunkX", source.get("chunkX"));
            banner.put("chunkZ", source.get("chunkZ"));
            banner.put("patterns", source.get("patterns"));
            out.add(banner);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spawnHistory(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> source)) {
            out.put("currentIndex", -1);
            out.put("cycles", List.of());
            return out;
        }
        out.put("currentIndex", source.get("currentIndex"));
        out.put("separation", source.get("separation"));
        List<Map<String, Object>> cycles = new ArrayList<>();
        Object rawCycles = source.get("cycles");
        if (rawCycles instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> cycle)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", cycle.get("index"));
                item.put("x", cycle.get("x"));
                item.put("z", cycle.get("z"));
                item.put("createdAt", cycle.get("createdAt"));
                item.put("source", cycle.get("source"));
                item.put("current", cycle.get("current"));
                item.put("overworld", cycle.get("overworld"));
                item.put("nether", cycle.get("nether"));
                cycles.add(item);
            }
        }
        out.put("cycles", cycles);
        return out;
    }
}
