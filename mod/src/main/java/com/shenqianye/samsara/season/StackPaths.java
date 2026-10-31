package com.shenqianye.samsara.season;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 新世界模型（v0.24）的路径约定：
 *
 * <pre>
 * world/                                      # 栈顶/最新，level-name=world
 * ├── level.dat
 * ├── players/                                # 所有栈共享
 * ├── data/minecraft/                         # 默认共享
 * ├── dimensions/
 * │   ├── minecraft/{overworld,the_nether,the_end}
 * │   └── samsara/stack_&lt;n&gt;/{overworld,the_nether,the_end}
 * └── samsara/
 *     ├── stack.json                          # 栈清单（含 top）
 *     ├── spawn-history.json                  # 栈顶出生点历史
 *     └── stack/stack_&lt;n&gt;/
 *         ├── level.dat
 *         ├── data/minecraft/world_gen_settings.dat
 *         ├── stack.json
 *         ├── spawn-history.json
 *         └── claims/ diff/ track/
 * </pre>
 */
public final class StackPaths {
    public static final String TOP_ID = "world";
    public static final String STACK_PREFIX = "stack_";
    public static final String NAMESPACE = "samsara";

    private StackPaths() {
    }

    // ---- roots ----

    public static Path worldDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    /** mod init 阶段（server 尚未创建）用 server.properties 的 level-name。 */
    public static Path worldDir() {
        Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path props = game.resolve("server.properties");
        String name = "world";
        if (Files.exists(props)) {
            try {
                for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) {
                    if (line.startsWith("level-name=")) {
                        name = line.substring("level-name=".length()).trim();
                        break;
                    }
                }
            } catch (IOException ignored) {
            }
        }
        if (name.isBlank() || name.contains("/") || name.contains("\\")) {
            name = "world";
        }
        return game.resolve(name).toAbsolutePath().normalize();
    }

    public static Path samsara(Path worldDir) {
        return worldDir.resolve("samsara");
    }

    public static Path samsara(MinecraftServer server) {
        return samsara(worldDir(server));
    }

    public static Path manifest(Path worldDir) {
        return samsara(worldDir).resolve("stack.json");
    }

    public static Path stackMetaRoot(Path worldDir) {
        return samsara(worldDir).resolve("stack");
    }

    public static Path dimensions(Path worldDir) {
        return worldDir.resolve("dimensions");
    }

    public static Path layerMeta(Path worldDir, StackLayer layer) {
        return layer.top ? samsara(worldDir)
                : stackMetaRoot(worldDir).resolve(layer.id);
    }

    public static Path layerDimensions(Path worldDir, StackLayer layer) {
        return layer.top ? dimensions(worldDir).resolve("minecraft")
                : dimensions(worldDir).resolve(NAMESPACE).resolve(layer.id);
    }

    public static Path layerDimension(Path worldDir, StackLayer layer, String dim) {
        return layerDimensions(worldDir, layer).resolve(dim);
    }

    public static Path spawnHistory(Path worldDir, StackLayer layer) {
        return layerMeta(worldDir, layer).resolve("spawn-history.json");
    }

    public static Path layerStackFile(Path worldDir, StackLayer layer) {
        return layerMeta(worldDir, layer).resolve("stack.json");
    }

    public static Path worldGenSettings(Path worldDir, StackLayer layer) {
        return layerMeta(worldDir, layer).resolve("data").resolve("minecraft")
                .resolve("world_gen_settings.dat");
    }

    /** 栈层 id 对应的数据根；无法识别时回退栈顶。 */
    public static Path dataRootFor(Path worldDir, String layerId) {
        if (layerId == null || layerId.isBlank() || TOP_ID.equals(layerId)) {
            return samsara(worldDir);
        }
        return stackMetaRoot(worldDir).resolve(layerId);
    }

    /** 旧栈层的维度根；栈顶 = {@code dimensions/minecraft}。 */
    public static Path layerDimensionsFor(Path worldDir, String layerId) {
        if (layerId == null || layerId.isBlank() || TOP_ID.equals(layerId)) {
            return dimensions(worldDir).resolve("minecraft");
        }
        return dimensions(worldDir).resolve(NAMESPACE).resolve(layerId);
    }

    public static Path layerDimensionFor(Path worldDir, String layerId, String dim) {
        return layerDimensionsFor(worldDir, layerId).resolve(dim);
    }

    /** 从持久化的维度字符串解析 ResourceKey；失败返回 null。 */
    public static ResourceKey<Level> keyFromString(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return null;
        }
        try {
            return ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
        } catch (Exception e) {
            return null;
        }
    }

    /** 某个维度所属栈层的数据根：栈顶 = {@code world/samsara}，旧层 = {@code .../stack/<id>}。 */
    public static Path dataRootFor(Path worldDir, ResourceKey<Level> key) {
        String id = layerIdOf(key);
        if (id == null || TOP_ID.equals(id)) {
            return samsara(worldDir);
        }
        return stackMetaRoot(worldDir).resolve(id);
    }

    /** 所有已有栈层的 claims/diff/track 根（含栈顶）。 */
    public static List<Path> dataRoots(Path worldDir) {
        List<Path> roots = new java.util.ArrayList<>();
        roots.add(samsara(worldDir));
        Path stackRoot = stackMetaRoot(worldDir);
        if (Files.isDirectory(stackRoot)) {
            try (var stream = Files.list(stackRoot)) {
                for (Path dir : (Iterable<Path>) stream.sorted()::iterator) {
                    if (Files.isDirectory(dir) && Files.exists(dir.resolve("stack.json"))) {
                        roots.add(dir);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return roots;
    }

    // ---- dimension keys ----

    public static ResourceKey<Level> key(StackLayer layer, String dim) {
        if (layer.top) {
            return switch (dim) {
                case "the_nether" -> Level.NETHER;
                case "the_end" -> Level.END;
                default -> Level.OVERWORLD;
            };
        }
        Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, layer.id + "/" + dim);
        return ResourceKey.create(Registries.DIMENSION, id);
    }

    /** 从 dimension key 解析栈层 id；top 返回 {@link #TOP_ID}，无法识别返回 null。 */
    public static String layerIdOf(ResourceKey<Level> key) {
        if (key == null) {
            return null;
        }
        Identifier id = key.identifier();
        if ("minecraft".equals(id.getNamespace())) {
            String path = id.getPath();
            if ("overworld".equals(path) || "the_nether".equals(path) || "the_end".equals(path)) {
                return TOP_ID;
            }
            return null;
        }
        if (!NAMESPACE.equals(id.getNamespace())) {
            return null;
        }
        String path = id.getPath();
        int slash = path.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String layer = path.substring(0, slash);
        String dim = path.substring(slash + 1);
        if (!List.of("overworld", "the_nether", "the_end").contains(dim)) {
            return null;
        }
        return layer;
    }

    public static String dimensionOf(ResourceKey<Level> key) {
        Identifier id = key == null ? null : key.identifier();
        return id == null ? null : id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
    }

    public static boolean isTop(ResourceKey<Level> key) {
        return TOP_ID.equals(layerIdOf(key));
    }
}
