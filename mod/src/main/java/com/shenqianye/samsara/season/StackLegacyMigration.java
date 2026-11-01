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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 旧布局（服务器根下 {@code world} + {@code world_sN} 兄弟文件夹，world 为栈底）
 * 一次性迁移到 v0.24：
 *
 * <pre>
 * world/                                      # 新栈顶（旧布局中数字最大的世界）
 * ├── level.dat
 * ├── dimensions/minecraft/...               # 旧栈顶维度
 * ├── data/minecraft/...                     # 旧栈顶数据（运行期共享）
 * ├── players/...                            # 所有旧世界的玩家数据合并
 * └── samsara/
 *     ├── stack.json
 *     ├── spawn-history.json
 *     └── stack/stack_&lt;n&gt;/
 *         ├── level.dat
 *         ├── data/minecraft/world_gen_settings.dat
 *         ├── players-conflict/               # 同名玩家文件的旧版本（不丢数据）
 *         ├── claims/ diff/ track/
 *         └── legacy-root/                    # 旧世界其余文件
 * </pre>
 *
 * <p>迁移只做目录 rename/少量元数据复制，不复制区块。所有移动写入 {@link Journal}，
 * 任一步失败会反向回滚；回滚失败时原目录仍在 staging 中，日志给出位置。
 */
public final class StackLegacyMigration {
    private StackLegacyMigration() {
    }

    public static boolean migrateIfNeeded() {
        Path configured = StackPaths.worldDir();
        Path parent = configured.getParent();
        if (parent == null) {
            return false;
        }
        return migrate(parent.resolve(StackPaths.TOP_ID), parent);
    }

    /**
     * 迁移入口（单测可用临时目录）。
     *
     * @param worldDir 新模型主世界目录（通常 {@code <服务器根>/world}）
     * @param parent   服务器根目录，旧 {@code world_sN} 与 server.properties 所在处
     */
    static boolean migrate(Path worldDir, Path parent) {
        if (parent == null || Files.exists(StackPaths.manifest(worldDir))) {
            return false;
        }
        List<String> old = oldOrder(parent);
        if (old.size() <= 1) {
            return false; // 只有 world：新模型直接以 world 为栈顶，无需迁移
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                .format(new java.util.Date());
        Path staging = parent.resolve(".samsara-migrate-" + stamp);
        Journal journal = new Journal(staging);
        try {
            Files.createDirectories(staging);
            Samsara.LOGGER.info("[周目管理 samsara] 检测到旧布局 {}，开始迁移到 v0.24 维度模型",
                    String.join(" → ", old));

            // 1) 所有旧世界先整体移入 staging，避免与新的 world 目标路径冲突。
            for (String name : old) {
                journal.move(parent.resolve(name), staging.resolve(name));
            }

            // 2) 读取每层元数据（移动前）。
            List<OldLayer> layers = new ArrayList<>();
            String levelType = readLevelType(parent);
            for (int i = 0; i < old.size(); i++) {
                Path src = staging.resolve(old.get(i));
                if (!Files.isRegularFile(src.resolve("level.dat"))) {
                    continue;
                }
                OldLayer layer = new OldLayer();
                layer.name = old.get(i);
                layer.index = i;
                layer.src = src;
                layer.seed = readSeed(src.resolve("data").resolve("minecraft")
                        .resolve("world_gen_settings.dat"));
                readSpawn(src.resolve("level.dat"), layer);
                layer.levelType = levelType;
                layers.add(layer);
            }
            if (layers.isEmpty()) {
                throw new IOException("旧布局没有可用于迁移的 level.dat");
            }
            OldLayer top = layers.get(layers.size() - 1);

            // 3) 新 world = 旧栈顶。
            Files.createDirectories(worldDir);
            Path topDims = top.src.resolve("dimensions").resolve("minecraft");
            if (Files.isDirectory(topDims)) {
                journal.move(topDims, worldDir.resolve("dimensions").resolve("minecraft"));
            }
            journal.moveIfExists(top.src.resolve("data"), worldDir.resolve("data"));
            journal.moveIfExists(top.src.resolve("datapacks"), worldDir.resolve("datapacks"));
            journal.moveIfExists(top.src.resolve("scripts"), worldDir.resolve("scripts"));
            journal.moveIfExists(top.src.resolve("level.dat"), worldDir.resolve("level.dat"));
            journal.moveIfExists(top.src.resolve("samsara"), worldDir.resolve("samsara"));
            Files.createDirectories(StackPaths.samsara(worldDir));

            // 4) 旧栈层：维度 + 元数据 + 世界数据。
            Path players = worldDir.resolve("players");
            Files.createDirectories(players);
            Path playerConflicts = StackPaths.samsara(worldDir).resolve("legacy-player-conflicts");
            for (OldLayer layer : layers) {
                mergePlayers(layer.src.resolve("players"), players, playerConflicts, journal);
                if (layer == top) {
                    moveTopLeftovers(layer.src, worldDir, journal);
                    continue;
                }
                String id = StackPaths.STACK_PREFIX + layer.index;
                layer.id = id;
                Path meta = StackPaths.layerMeta(worldDir, layer.toStackLayer());
                Files.createDirectories(meta);
                copy(layer.src.resolve("level.dat"), meta.resolve("level.dat"), journal);
                Path targetDims = worldDir.resolve("dimensions")
                        .resolve(StackPaths.NAMESPACE).resolve(id);
                Path srcDims = layer.src.resolve("dimensions").resolve("minecraft");
                if (Files.isDirectory(srcDims)) {
                    journal.move(srcDims, targetDims);
                }
                journal.moveIfExists(layer.src.resolve("data"), meta.resolve("data"));
                journal.moveIfExists(layer.src.resolve("samsara"), meta.resolve("samsara"));
                // spawn-history 若在旧 samsara 根，move 后已在 meta/samsara；这里拉平到 meta 根。
                flattenSamsara(meta, journal);
                Path legacyRoot = meta.resolve("legacy-root");
                moveAllLeftovers(layer.src, legacyRoot, journal, true);
            }

            // 5) 清单：旧层（不含 top） + 新栈顶 world。
            StackManifest manifest = new StackManifest();
            for (OldLayer layer : layers) {
                StackLayer sl = layer.toStackLayer();
                if (layer == top) {
                    continue;
                }
                manifest.layers.add(sl);
                writeLayerStack(metaFor(worldDir, sl), sl);
            }
            StackLayer worldTop = top.toStackLayer();
            worldTop.id = StackPaths.TOP_ID;
            worldTop.top = true;
            worldTop.index = layers.size() - 1;
            manifest.layers.add(worldTop);
            manifest.normalize();
            manifest.save(worldDir);

            // 6) server.properties：固定 level-name=world；种子只在读到时覆盖。
            writeProperties(parent, top.seed, top.levelType);
            cleanupStaging(staging, parent, stamp);
            Samsara.LOGGER.info("[周目管理 samsara] 旧布局迁移完成：{}（旧 world_sN 内容已进入栈层，未复制区块）",
                    manifest.summary());
            return true;
        } catch (Exception e) {
            boolean rolledBack = journal.rollback();
            Samsara.LOGGER.error("[周目管理 samsara] 旧布局迁移失败（{}），{}；迁移暂存目录 {}",
                    rootMessage(e), rolledBack ? "已回滚原目录" : "回滚未完全成功，原目录保留在暂存目录",
                    staging.toAbsolutePath().normalize());
            return false;
        }
    }

    private static final class OldLayer {
        String name;
        String id;
        int index;
        Path src;
        long seed;
        String levelType = "normal";
        int spawnX;
        int spawnY = 64;
        int spawnZ;
        long createdAt = System.currentTimeMillis();

        StackLayer toStackLayer() {
            StackLayer l = new StackLayer();
            l.id = id;
            l.index = index;
            l.seed = seed;
            l.levelType = levelType;
            l.spawnX = spawnX;
            l.spawnY = spawnY;
            l.spawnZ = spawnZ;
            l.createdAt = createdAt;
            l.normalize();
            return l;
        }
    }

    private static Path metaFor(Path worldDir, StackLayer layer) {
        return StackPaths.layerMeta(worldDir, layer);
    }

    private static void writeLayerStack(Path meta, StackLayer layer) throws IOException {
        Path file = meta.resolve("stack.json");
        Files.createDirectories(meta);
        Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(layer), StandardCharsets.UTF_8);
    }

    /** 把非顶层 meta/samsara/* 拉到 meta 根（兼容 v0.23 每世界自己 samsara/ 的布局）。 */
    private static void flattenSamsara(Path meta, Journal journal) throws IOException {
        Path nested = meta.resolve("samsara");
        if (!Files.isDirectory(nested)) {
            return;
        }
        try (var stream = Files.list(nested)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                Path target = meta.resolve(child.getFileName().toString());
                if (!Files.exists(target)) {
                    journal.move(child, target);
                }
            }
        }
        try (var stream = Files.list(nested)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(nested);
            }
        }
    }

    private static void moveTopLeftovers(Path src, Path worldDir, Journal journal) throws IOException {
        try (var stream = Files.list(src)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                String name = child.getFileName().toString();
                if ("session.lock".equals(name) || "level.dat_old".equals(name)) {
                    continue;
                }
                Path target = worldDir.resolve(name);
                if (!Files.exists(target)) {
                    journal.move(child, target);
                } else if (Files.isDirectory(child) && Files.isDirectory(target)) {
                    // 极少见：顶层已有同名目录，逐项并入，冲突保留在 legacy 目录。
                    moveAllLeftovers(child, target, journal, false);
                }
            }
        }
    }

    private static void moveAllLeftovers(Path src, Path dst, Journal journal, boolean skipKnown)
            throws IOException {
        if (!Files.isDirectory(src)) {
            return;
        }
        try (var stream = Files.list(src)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                String name = child.getFileName().toString();
                if (skipKnown && ("session.lock".equals(name) || "level.dat_old".equals(name))) {
                    continue;
                }
                Path target = dst.resolve(name);
                if (Files.exists(target)) {
                    if (Files.isDirectory(child) && Files.isDirectory(target)) {
                        moveAllLeftovers(child, target, journal, skipKnown);
                    }
                } else {
                    java.nio.file.Files.createDirectories(target.getParent());
                    journal.move(child, target);
                }
            }
        }
    }

    /**
     * 玩家数据合并到共享 world/players（26.3 实际布局为 {@code players/data/<uuid>.dat}）。
     * 递归合并子目录；同名文件保留较新的一份，旧版本移到
     * {@code world/samsara/legacy-player-conflicts/}，绝不直接删除。
     */
    private static void mergePlayers(Path srcPlayers, Path dstPlayers, Path conflicts, Journal journal)
            throws IOException {
        if (!Files.isDirectory(srcPlayers)) {
            return;
        }
        Files.createDirectories(dstPlayers);
        try (var stream = Files.list(srcPlayers)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                Path target = dstPlayers.resolve(child.getFileName().toString());
                if (Files.isDirectory(child)) {
                    if (!Files.exists(target)) {
                        journal.move(child, target);
                    } else {
                        mergePlayers(child, target, conflicts, journal);
                    }
                    continue;
                }
                if (!Files.isRegularFile(child)) {
                    continue;
                }
                if (!Files.exists(target)) {
                    journal.move(child, target);
                    continue;
                }
                FileTime srcTime = Files.getLastModifiedTime(child);
                FileTime dstTime = Files.getLastModifiedTime(target);
                Files.createDirectories(conflicts);
                if (srcTime.compareTo(dstTime) > 0) {
                    Path archived = conflicts.resolve(target.getFileName() + ".old");
                    journal.move(target, unique(archived));
                    journal.move(child, target);
                } else {
                    journal.move(child, unique(conflicts.resolve(child.getFileName().toString())));
                }
            }
        }
    }

    private static Path unique(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String name = path.getFileName().toString();
        for (int i = 1; i < 10_000; i++) {
            Path p = path.resolveSibling(name + "." + i);
            if (!Files.exists(p)) {
                return p;
            }
        }
        return path.resolveSibling(name + "." + System.nanoTime());
    }

    private static void cleanupStaging(Path staging, Path parent, String stamp) throws IOException {
        deleteEmptyTree(staging);
        if (Files.exists(staging)) {
            try (var stream = Files.list(staging)) {
                if (stream.findAny().isEmpty()) {
                    Files.deleteIfExists(staging);
                } else {
                    Path keep = parent.resolve("legacy-layout.migrated-" + stamp);
                    if (!Files.exists(keep)) {
                        Files.move(staging, keep);
                    }
                }
            }
        }
    }

    private static void deleteEmptyTree(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(child)) {
                    deleteEmptyTree(child);
                }
            }
        }
        try (var stream = Files.list(dir)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
            }
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

    private static void readSpawn(Path levelDat, OldLayer layer) {
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
                    String v = line.substring("level-type=".length()).trim().replace("\\:", ":");
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
                lines.set(i, "level-name=world");
                name = true;
            } else if (line.startsWith("level-seed=")) {
                if (seed != 0) {
                    lines.set(i, "level-seed=" + seed);
                }
                seedSet = true;
            } else if (line.startsWith("level-type=")) {
                lines.set(i, "level-type=" + levelType);
                type = true;
            }
        }
        if (!name) lines.add("level-name=world");
        if (!seedSet && seed != 0) lines.add("level-seed=" + seed);
        if (!type) lines.add("level-type=" + levelType);
        Files.write(props, lines, StandardCharsets.UTF_8);
    }

    private static void copy(Path src, Path dst, Journal journal) throws IOException {
        if (Files.exists(src)) {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            journal.copied.add(dst);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    /** 迁移移动日志：失败时反向撤销已完成的 rename/copy。 */
    private static final class Journal {
        private final Path staging;
        private final List<Path[]> moved = new ArrayList<>();
        private final List<Path> copied = new ArrayList<>();

        Journal(Path staging) {
            this.staging = staging;
        }

        void move(Path src, Path dst) throws IOException {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst);
            moved.add(new Path[]{src, dst});
        }

        void moveIfExists(Path src, Path dst) throws IOException {
            if (Files.exists(src)) {
                move(src, dst);
            }
        }

        boolean rollback() {
            boolean ok = true;
            for (Path p : copied) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    ok = false;
                }
            }
            for (int i = moved.size() - 1; i >= 0; i--) {
                Path[] op = moved.get(i);
                try {
                    if (Files.exists(op[1])) {
                        Files.createDirectories(op[0].getParent());
                        if (Files.exists(op[0])) {
                            // 目标位置已有回滚内容（极少见）：保留，后续人工处理。
                            ok = false;
                            continue;
                        }
                        Files.move(op[1], op[0]);
                    }
                } catch (IOException e) {
                    ok = false;
                }
            }
            try {
                deleteEmptyTree(staging);
            } catch (IOException ignored) {
                // 保留暂存目录现场
            }
            return ok;
        }
    }
}
