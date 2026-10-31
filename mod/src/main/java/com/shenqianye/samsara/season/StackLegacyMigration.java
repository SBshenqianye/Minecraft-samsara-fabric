package com.shenqianye.samsara.season;

import com.shenqianye.samsara.Samsara;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 旧布局（服务器根下 {@code world} + {@code world_sN} 兄弟文件夹，world 为栈底）
 * 一次性迁移到 v0.24：
 *
 * <pre>
 * world/                                      # 新栈顶（旧布局的最新世界）
 * ├── dimensions/minecraft/...
 * ├── data/minecraft/...
 * ├── players/...
 * └── samsara/stack/stack_<n>/{level.dat,data,players,claims,diff,track,...}
 * world/dimensions/samsara/stack_<n>/...      # 旧层区块
 * </pre>
 *
 * <p>只做目录 rename/copy 元数据，不复制 region 文件。旧 {@code world_sN} 目录在移走数据后
 * 改名为 {@code .migrated}，保留现场。
 */
public final class StackLegacyMigration {
    private StackLegacyMigration() {
    }

    public static boolean migrateIfNeeded() {
        Path worldDir = StackPaths.worldDir();
        Path parent = worldDir.getParent();
        if (parent == null || Files.exists(StackPaths.manifest(worldDir))) {
            return false;
        }
        List<String> old = oldOrder(parent);
        if (old.size() <= 1) {
            return false; // 只有 world：新模型直接以 world 为栈顶，无需迁移
        }
        String topName = old.get(old.size() - 1);
        try {
            Samsara.LOGGER.info("[周目管理 samsara] 检测到旧布局 {}，开始迁移到 v0.24 维度模型",
                    String.join(" → ", old));
            List<StackLayer> layers = new ArrayList<>();
            for (int i = 0; i < old.size(); i++) {
                String name = old.get(i);
                Path src = parent.resolve(name);
                if (!Files.isRegularFile(src.resolve("level.dat"))) {
                    continue;
                }
                StackLayer layer = new StackLayer();
                layer.id = StackPaths.STACK_PREFIX + i;
                layer.index = i;
                layer.createdAt = System.currentTimeMillis();
                layer.levelType = readLevelType(parent);
                layer.seed = readSeed(src.resolve("data").resolve("minecraft")
                        .resolve("world_gen_settings.dat"));
                readSpawn(src.resolve("level.dat"), layer);
                layer.normalize();
                layers.add(layer);

                // 维度 -> 临时栈维度目录
                moveChildren(src.resolve("dimensions").resolve("minecraft"),
                        worldDir.resolve("dimensions").resolve("samsara").resolve(layer.id));
                // 元数据
                Path meta = StackPaths.layerMeta(worldDir, layer);
                Files.createDirectories(meta);
                copyIfExists(src.resolve("level.dat"), meta.resolve("level.dat"));
                copyIfExists(src.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat"),
                        StackPaths.worldGenSettings(worldDir, layer));
                // 世界级数据/玩家/数据包：先归档到旧层
                moveDir(src.resolve("data"), meta.resolve("data"));
                moveDir(src.resolve("players"), meta.resolve("players"));
                moveDir(src.resolve("datapacks"), meta.resolve("datapacks"));
                moveDir(src.resolve("scripts"), meta.resolve("scripts"));
                // 世界特定 samsara 数据
                for (String dir : List.of("claims", "diff", "track")) {
                    moveDir(src.resolve("samsara").resolve(dir), meta.resolve(dir));
                }
                for (String file : List.of("spawn-history.json", "season.json", "rewind.json",
                        "pending-delete.json", "reset-pending.json")) {
                    moveFile(src.resolve("samsara").resolve(file), meta.resolve(file));
                }
                Files.writeString(StackPaths.layerStackFile(worldDir, layer),
                        new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(layer),
                        StandardCharsets.UTF_8);
            }
            if (layers.isEmpty()) {
                return false;
            }
            StackLayer top = layers.get(layers.size() - 1);

            // 把最新层恢复成 world 顶层
            moveChildren(worldDir.resolve("dimensions").resolve("samsara").resolve(top.id),
                    worldDir.resolve("dimensions").resolve("minecraft"));
            Path topMeta = StackPaths.layerMeta(worldDir, top);
            moveChildren(topMeta.resolve("data"), worldDir.resolve("data"));
            moveChildren(topMeta.resolve("players"), worldDir.resolve("players"));
            moveChildren(topMeta.resolve("datapacks"), worldDir.resolve("datapacks"));
            moveChildren(topMeta.resolve("scripts"), worldDir.resolve("scripts"));
            for (String dir : List.of("claims", "diff", "track")) {
                moveChildren(topMeta.resolve(dir), worldDir.resolve("samsara").resolve(dir));
            }
            for (String file : List.of("spawn-history.json", "season.json", "rewind.json",
                    "pending-delete.json", "reset-pending.json")) {
                moveFile(topMeta.resolve(file), worldDir.resolve("samsara").resolve(file));
            }
            copyIfExists(topMeta.resolve("level.dat"), worldDir.resolve("level.dat"));
            copyIfExists(StackPaths.worldGenSettings(worldDir, top),
                    worldDir.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat"));
            // 顶层不再需要自己的 stack 元数据目录
            deleteChildren(topMeta);
            Files.deleteIfExists(worldDir.resolve("dimensions").resolve("samsara").resolve(top.id));
            // 旧 world_sN 改名保留现场
            for (String name : old) {
                if (name.equals(StackPaths.TOP_ID)) {
                    continue;
                }
                Path src = parent.resolve(name);
                Path dst = parent.resolve(name + ".migrated");
                if (Files.exists(src) && !Files.exists(dst)) {
                    Files.move(src, dst);
                }
            }
            // 清单：旧层（不含 top） + top world
            StackManifest manifest = new StackManifest();
            for (int i = 0; i < layers.size() - 1; i++) {
                manifest.layers.add(layers.get(i));
            }
            StackLayer worldTop = top.copy();
            worldTop.id = StackPaths.TOP_ID;
            worldTop.top = true;
            worldTop.index = layers.size() - 1;
            manifest.layers.add(worldTop);
            manifest.normalize();
            manifest.save(worldDir);
            writeProperties(parent, top.seed, top.levelType);
            Samsara.LOGGER.info("[周目管理 samsara] 旧布局迁移完成：{}（旧 world_sN 已改名为 .migrated）",
                    manifest.summary());
            return true;
        } catch (Exception e) {
            Samsara.LOGGER.error("[周目管理 samsara] 旧布局迁移失败，已保留原目录: {}", e.getMessage());
            return false;
        }
    }

    private static List<String> oldOrder(Path parent) {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(parent)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(p) || !Files.isRegularFile(p.resolve("level.dat"))) {
                    continue;
                }
                String n = p.getFileName().toString();
                if (n.equals("world") || n.matches("world_s\\d+")) {
                    names.add(n);
                }
            }
        } catch (IOException ignored) {
        }
        names.sort((a, b) -> {
            if (a.equals(b)) return 0;
            if (a.equals("world")) return -1;
            if (b.equals("world")) return 1;
            int ia = Integer.parseInt(a.substring("world_s".length()));
            int ib = Integer.parseInt(b.substring("world_s".length()));
            return Integer.compare(ia, ib);
        });
        return names;
    }

    private static long readSeed(Path worldGenSettings) {
        if (!Files.exists(worldGenSettings)) {
            return 0;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(worldGenSettings, NbtAccounter.unlimitedHeap());
            return root.getCompoundOrEmpty("data").getLongOr("seed", 0L);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void readSpawn(Path levelDat, StackLayer layer) {
        if (!Files.exists(levelDat)) {
            return;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
            CompoundTag data = root.getCompoundOrEmpty("Data");
            layer.spawnX = data.getIntOr("SpawnX", 0);
            layer.spawnY = data.getIntOr("SpawnY", 64);
            layer.spawnZ = data.getIntOr("SpawnZ", 0);
        } catch (Exception ignored) {
        }
    }

    private static String readLevelType(Path parent) {
        Path props = parent.resolve("server.properties");
        if (!Files.exists(props)) {
            return "normal";
        }
        try {
            for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) {
                if (line.startsWith("level-type=")) {
                    String v = line.substring("level-type=".length()).trim();
                    return v.isBlank() ? "normal" : v;
                }
            }
        } catch (IOException ignored) {
        }
        return "normal";
    }

    private static void writeProperties(Path parent, long seed, String levelType) throws IOException {
        Path props = parent.resolve("server.properties");
        List<String> lines = Files.exists(props)
                ? new ArrayList<>(Files.readAllLines(props, StandardCharsets.UTF_8))
                : new ArrayList<>();
        boolean name = false, seedSet = false, type = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("level-name=")) {
                lines.set(i, "level-name=world"); name = true;
            } else if (line.startsWith("level-seed=")) {
                lines.set(i, "level-seed=" + seed); seedSet = true;
            } else if (line.startsWith("level-type=")) {
                lines.set(i, "level-type=" + levelType); type = true;
            }
        }
        if (!name) lines.add("level-name=world");
        if (!seedSet) lines.add("level-seed=" + seed);
        if (!type) lines.add("level-type=" + levelType);
        Files.write(props, lines, StandardCharsets.UTF_8);
    }

    private static void copyIfExists(Path src, Path dst) throws IOException {
        if (Files.exists(src)) {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveFile(Path src, Path dst) throws IOException {
        if (Files.exists(src)) {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveDir(Path src, Path dst) throws IOException {
        if (!Files.exists(src)) {
            return;
        }
        Files.createDirectories(dst.getParent());
        if (!Files.exists(dst)) {
            Files.move(src, dst);
            return;
        }
        moveChildren(src, dst);
    }

    private static void moveChildren(Path src, Path dst) throws IOException {
        if (!Files.exists(src)) {
            return;
        }
        Files.createDirectories(dst);
        try (var stream = Files.list(src)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                Path target = dst.resolve(child.getFileName().toString());
                if (Files.isDirectory(child) && Files.exists(target)) {
                    moveChildren(child, target);
                } else if (!Files.exists(target)) {
                    Files.move(child, target);
                }
            }
        }
    }

    private static void deleteChildren(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(child)) {
                    deleteChildren(child);
                }
                Files.deleteIfExists(child);
            }
        }
    }
}
