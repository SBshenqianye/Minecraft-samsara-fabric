package com.shenqianye.samsara.rewind;

import com.shenqianye.samsara.Samsara;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块变化记录器（DiffRecorder）。
 * <p>
 * 由 {@link com.shenqianye.samsara.mixin.ServerLevelMixin} 在 {@code ServerLevel.setBlock}
 * 处调用，但只记录<b>玩家操作</b>与<b>爆炸</b>上下文内的方块类型变化
 * （{@code mixin/ServerPlayerGameModeMixin}、{@code mixin/ServerExplosionMixin} 负责压栈/出栈），
 * 从而过滤红石、作物、流体扩散、树叶腐烂等世界模拟噪声。
 * <p>
 * <b>记录窗口</b>：记录<b>常开</b>——服务器启动即写入 {@code diff/live/<世界>/}；
 * 倒流真正启动时（扫描成功后）把当前世界层 <b>冻结</b> 为 {@code diff/cycle<周目id>/<世界>/}
 * （=本次倒流的恢复源），并开启新的 live；倒流完成（物理删除执行）时<b>重置</b>窗口
 * （丢弃倒流期间的改动）。中止倒流时把冻结库<b>并回</b> live，下次倒流仍能看到完整窗口。
 * <p>
 * <b>世界隔离</b>：世界栈中的每个世界有独立目录，栈里各世界的 {@code minecraft:overworld}
 * 不会互相覆盖。
 * <p>
 * <b>维度隔离</b>：每个维度写入 {@code <窗口>/<世界>/<命名空间>/<维度路径>/}，恢复侧只读主世界
 * {@code minecraft/overworld/}，下界/末地的改动不会与主世界同坐标碰撞。
 */
public class DiffRecorder {
    private static Path configDir;
    private static volatile Path currentRoot;
    /** 当前世界键（世界栈中的世界目录名）；服务器启动前为 null */
    private static volatile String worldKey;
    /** 每个维度一个常开窗口库；常开窗口不缓存新写入（避免常开窗口内存无界增长） */
    private static final Map<String, DiffStore> stores = new ConcurrentHashMap<>();
    private static volatile boolean enabled;
    /** 记录上下文深度：>0 表示当前 setBlock 由玩家操作或爆炸触发 */
    private static final ThreadLocal<Integer> EDIT_CONTEXT = ThreadLocal.withInitial(() -> 0);

    private DiffRecorder() {
    }

    /** 服务器启动时初始化：开启常开记录窗口（diff/live），并迁移旧的无维度/无世界布局 */
    public static synchronized void init(Path samsaraConfigDir) {
        if (currentRoot != null) {
            flush(); // 内置服务器重启：先把上一个窗口的缓冲落盘
        }
        configDir = samsaraConfigDir;
        // 世界层要等服务器起来才知道世界目录名（world / world_s1 / 单人存档名），
        // 由 initWorld(server) 在 SERVER_STARTED 补齐；此前不打开任何库。
        currentRoot = null;
        worldKey = null;
        stores.clear();
        enabled = true;
        clearEditContext(); // 内置服务器重启/异常残留：新会话从干净上下文开始
        Samsara.LOGGER.info("[samsara] 差异记录已初始化（等待服务器启动后确定世界层）");
    }

    /**
     * 服务器启动：确定当前世界目录名，把窗口根切到 {@code diff/live/<世界>}，
     * 并把旧窗口名（current→live、s&lt;id&gt;→cycle&lt;id&gt;）与旧世界层迁入新布局。
     */
    public static synchronized void initWorld(MinecraftServer server) {
        if (configDir == null) {
            return;
        }
        String key = DiffStore.sanitizeWorldKey(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .getFileName().toString());
        if (key.equals(worldKey) && currentRoot != null) {
            return;
        }
        flush();
        worldKey = key;
        currentRoot = DiffStore.liveWorldRoot(configDir, key);
        // 旧窗口名 + 所有窗口的旧布局一次性迁移（幂等、不丢数据）
        Path diffRoot = configDir.resolve("diff");
        DiffStore.migrateLayout(diffRoot, key);
        stores.clear();
        storeFor(DiffStore.OVERWORLD_DIM);
        clearEditContext();
        Samsara.LOGGER.info("[samsara] 差异记录已开启（常开窗口 diff/live/{}，按维度分目录）", key);
    }

    /** 当前世界键（世界栈中的世界目录名；服务器启动前为 null） */
    public static String worldKey() {
        return worldKey;
    }

    /**
     * 冻结当前记录窗口为 {@code diff/cycle<seasonId>/<世界>} 并开启新窗口（倒流启动时调用）。
     * 冻结后的 cycle<seasonId>/<世界> 即本次倒流的恢复源；若目标已存在（中止后重开/上次冻结残留），
     * 把 live 合并进目标而不是丢弃旧数据。其他世界的数据留在 live，不受影响。
     */
    public static synchronized void freezeCurrent(int seasonId) {
        if (configDir == null || currentRoot == null) {
            return;
        }
        flush();
        Path dst = DiffStore.cycleWorldRoot(configDir, seasonId, worldKey);
        try {
            if (Files.exists(currentRoot)) {
                if (Files.exists(dst)) {
                    DiffStore.mergeWindows(currentRoot, dst);
                    deleteRecursively(currentRoot);
                    Samsara.LOGGER.warn("[周目管理 samsara] 冻结时目标恢复库已存在，已合并 live（不丢弃旧窗口）");
                } else {
                    Files.createDirectories(currentRoot.getParent());
                    Files.createDirectories(dst.getParent());
                    Files.move(currentRoot, dst);
                }
            }
            Files.createDirectories(currentRoot);
            stores.clear();
            storeFor(DiffStore.OVERWORLD_DIM);
            Samsara.LOGGER.info("[周目管理 samsara] 差异库冻结: diff/live/{} → diff/cycle{}/{}（本次倒流恢复源，窗口=上次删块后至今）",
                    worldKey, seasonId, worldKey);
        } catch (IOException e) {
            Samsara.LOGGER.error("[周目管理 samsara] 差异库冻结失败（保留 live 继续记录）: {}", e.getMessage());
            stores.clear();
            storeFor(DiffStore.OVERWORLD_DIM);
        }
    }

    /**
     * 重置记录窗口（倒流完成、物理删除执行时调用）：丢弃倒流期间的改动，
     * 开启新窗口（下次倒流的恢复源将从此刻起累计）。
     */
    public static synchronized void resetWindow() {
        if (configDir == null || currentRoot == null) {
            return;
        }
        flush();
        try {
            deleteRecursively(currentRoot);
            Files.createDirectories(currentRoot);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 重置记录窗口失败: {}", e.getMessage());
        }
        stores.clear();
        enabled = true;
        storeFor(DiffStore.OVERWORLD_DIM);
        Samsara.LOGGER.info("[周目管理 samsara] 差异记录窗口已重置（丢弃倒流期间改动，重新累计）");
    }

    /**
     * 中止倒流：把冻结库 {@code diff/cycle<seasonId>/<世界>} 并回 live，保证“中止不重置”
     * （《设计规范》〈diff 记录窗口与恢复源〉）：下次倒流仍能看到中止前的全部改动。
     * 合并失败时保留 cycle<seasonId>，由下次冻结再次合并。
     */
    public static synchronized void mergeFrozenBack(int seasonId) {
        mergeFrozenBack(seasonId, worldKey);
    }

    /** 指定世界的中止合并（重启后 rewind.json 的世界可能与当前世界不同时用） */
    public static synchronized void mergeFrozenBack(int seasonId, String wk) {
        if (configDir == null || currentRoot == null || seasonId <= 0) {
            return;
        }
        String key = wk == null || wk.isBlank() ? worldKey : wk;
        if (key == null) {
            key = "world";
        }
        Path frozen = DiffStore.cycleWorldRoot(configDir, seasonId, key);
        if (!Files.exists(frozen)) {
            return;
        }
        flush();
        try {
            Path current = DiffStore.liveWorldRoot(configDir, key);
            Files.createDirectories(current);
            DiffStore.mergeWindows(frozen, current);
            deleteRecursively(frozen);
            deleteEmptyCycleDir(seasonId);
            stores.clear();
            storeFor(DiffStore.OVERWORLD_DIM);
            Samsara.LOGGER.info("[周目管理 samsara] 中止倒流：恢复库 diff/cycle{}/{} 已并回记录窗口（下次倒流仍可见）",
                    seasonId, key);
        } catch (IOException e) {
            Samsara.LOGGER.error("[周目管理 samsara] 中止倒流：恢复库并回失败（保留 diff/cycle{} 供下次冻结合并）: {}",
                    seasonId, e.getMessage());
            stores.clear();
            storeFor(DiffStore.OVERWORLD_DIM);
        }
    }

    /** 倒流成功归档后删除恢复源（只删指定世界层），避免 diff/cycle<N> 按周目无限累积 */
    public static synchronized void deleteRecovery(int seasonId) {
        deleteRecovery(seasonId, worldKey);
    }

    /** 指定世界的恢复源清理（重启后 rewind.json 的世界可能与当前世界不同时用） */
    public static synchronized void deleteRecovery(int seasonId, String wk) {
        if (configDir == null || seasonId <= 0) {
            return;
        }
        String key = wk == null || wk.isBlank() ? worldKey : wk;
        if (key == null) {
            key = "world";
        }
        Path frozen = DiffStore.cycleWorldRoot(configDir, seasonId, key);
        try {
            deleteRecursively(frozen);
            deleteEmptyCycleDir(seasonId);
            Samsara.LOGGER.info("[周目管理 samsara] 恢复源已清理: diff/cycle{}/{}", seasonId, key);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 恢复源清理失败 diff/cycle{}: {}", seasonId, e.getMessage());
        }
    }

    /** 周目目录下已无任何世界数据时删除空目录（世界栈下其他世界可能还在用） */
    private static void deleteEmptyCycleDir(int seasonId) {
        Path seasonDir = DiffStore.cycleRoot(configDir, seasonId);
        try (var entries = Files.newDirectoryStream(seasonDir)) {
            if (!entries.iterator().hasNext()) {
                Files.deleteIfExists(seasonDir);
            }
        } catch (IOException ignored) {
            // 目录不存在或有数据：保留
        }
    }

    /** 把当前窗口所有维度的缓冲区落盘（停服/定期 flush 调用） */
    public static synchronized void flush() {
        for (DiffStore s : stores.values()) {
            try {
                s.flush();
            } catch (Exception e) {
                Samsara.LOGGER.warn("[周目管理 samsara] diff flush 失败: {}", e.getMessage());
            }
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            for (Path f : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(f);
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** 临时开关记录（倒流恢复自身 setBlock 时暂停，避免“恢复→被记录→再被恢复成空气”自噬） */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean recording() {
        return enabled;
    }

    /**
     * 记录上下文压栈：由玩家操作/爆炸的 mixin 在方法 HEAD 调用。
     * 与 {@link #endEditContext()} 必须成对；异常路径由服务端 tick 兜底清理。
     */
    public static void beginEditContext() {
        EDIT_CONTEXT.set(EDIT_CONTEXT.get() + 1);
    }

    /** 记录上下文出栈（方法 RETURN） */
    public static void endEditContext() {
        int depth = EDIT_CONTEXT.get() - 1;
        if (depth <= 0) {
            EDIT_CONTEXT.remove();
        } else {
            EDIT_CONTEXT.set(depth);
        }
    }

    /** 防御性清理：异常导致上下文未配对时，下一 tick 清掉，避免误记世界模拟 */
    public static void clearEditContext() {
        EDIT_CONTEXT.remove();
    }

    private static boolean inEditContext() {
        return EDIT_CONTEXT.get() > 0;
    }

    /** 当前窗口根目录（diff/live/<世界>） */
    public static Path currentRoot() {
        return currentRoot;
    }

    /** 当前窗口全部维度的记录数（磁盘 + 尚未落盘的缓冲） */
    public static long countCurrent() {
        if (currentRoot == null) {
            return 0;
        }
        long total = DiffStore.countAll(currentRoot);
        for (DiffStore s : stores.values()) {
            total += s.pendingCount();
        }
        return total;
    }

    /** 当前窗口的主世界库（状态展示/兼容入口） */
    public static DiffStore store() {
        return storeFor(DiffStore.OVERWORLD_DIM);
    }

    private static DiffStore storeFor(String dimKey) {
        if (currentRoot == null) {
            return null;
        }
        return stores.computeIfAbsent(dimKey, k -> DiffStore.open(currentRoot.resolve(k), false));
    }

    private static String dimensionKey(ResourceKey<Level> key) {
        // 26.2 起 ResourceLocation 更名为 Identifier，ResourceKey#location() 改为 identifier()
        return key.identifier().getNamespace() + "/" + key.identifier().getPath();
    }

    /**
     * 由 mixin 调用的记录入口。
     * <p>
     * 只有满足以下条件才记录：
     * <ul>
     *   <li>记录开启（非倒流恢复自身）；</li>
     *   <li>处于玩家操作/爆炸上下文（{@link #beginEditContext()}）；</li>
     *   <li>方块<b>类型</b>真实变化（过滤同类型状态噪声）。</li>
     * </ul>
     * 世界模拟（红石/作物/流体/树叶腐烂等）没有上下文，因此不会被记录。
     */
    public static void record(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState before,
                              net.minecraft.world.level.block.state.BlockState after) {
        if (!enabled || currentRoot == null) {
            return;
        }
        // 先做便宜的“类型是否变化”判断：同类型状态变化（红石/作物/流体水位）占多数，
        // 可避免为纯世界模拟去碰 ThreadLocal 与维度路径
        if (before.getBlock() == after.getBlock()) {
            return;
        }
        if (!inEditContext()) {
            return;
        }
        // 忽略世界生成/越界
        if (!level.hasChunkAt(pos)) {
            return;
        }
        int stateId = net.minecraft.world.level.block.Block.getId(before);
        if (stateId < 0) {
            return;
        }
        DiffStore s = storeFor(dimensionKey(level.dimension()));
        if (s != null) {
            s.record(pos.getX(), pos.getY(), pos.getZ(), stateId, System.currentTimeMillis());
        }
    }

    /** 石头方块的状态 id（动态获取，26.2 注册表顺序不能硬编码 1） */
    public static int stoneStateId() {
        return net.minecraft.world.level.block.Block.getId(
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
    }

    /**
     * fake 注入目标库：倒流进行中注入到<b>恢复源</b>（diff/cycle&lt;N&gt;，倒流可见）；
     * 无倒流时注入常开记录窗口（live，下次倒流可见）。默认主世界。
     */
    public static DiffStore fakeTarget() {
        return fakeTarget(null);
    }

    /** 指定维度的 fake 注入目标；{@code level=null} 等价主世界 */
    public static DiffStore fakeTarget(ServerLevel level) {
        if (configDir == null) {
            return null;
        }
        RewindEngine re = RewindEngine.getInstance();
        String dim = level == null ? DiffStore.OVERWORLD_DIM : dimensionKey(level.dimension());
        if (re.running() && re.currentSeasonId() > 0) {
            String wk = re.currentWorldKey();
            if (wk == null || wk.isBlank()) {
                wk = worldKey == null ? "world" : worldKey;
            }
            return DiffStore.forSeason(configDir, wk, re.currentSeasonId(), dim);
        }
        return storeFor(dim);
    }

    /** fake 写盘后让恢复侧重新读盘（否则已缓存的区块看不到新注入/索引会错位） */
    private static void refreshRestoreAfterFake() {
        if (RewindEngine.getInstance().running()) {
            DiffRestore.reset();
        }
    }

    /**
     * 注入虚假 diff 记录（测试可视化倒流用，不需真的改方块）。
     * 在指定区块随机位置写 count 条记录，stateId = 指定方块（恢复时会把这些格还原成该方块，
     * 若原本是空气则"变出方块"，肉眼可见倒流恢复）。
     * 时间戳=now（视为最新改动 → 倒流最先恢复）。
     *
     * @return 描述文本
     */
    public static String fakeDiff(int cx, int cz, int count, int stateId) {
        DiffStore target = fakeTarget();
        if (target == null) {
            return "差异库未初始化（先 /season start 或 /samsara debug flow setup + flow rewind）";
        }
        if (count <= 0 || count > 4096) {
            return "数量须在 1~4096 之间";
        }
        java.util.Random rnd = new java.util.Random();
        long now = System.currentTimeMillis();
        int baseX = cx << 4;
        int baseZ = cz << 4;
        for (int i = 0; i < count; i++) {
            int x = baseX + rnd.nextInt(16);
            int z = baseZ + rnd.nextInt(16);
            int y = 64 + rnd.nextInt(96); // 64~159，地表附近便于观察
            target.record(x, y, z, stateId, now - i); // 时间递减：i=0 最新
        }
        target.flush(); // 批量落盘，确保立即可读
        refreshRestoreAfterFake();
        return "已注入 " + count + " 条虚假 diff 到区块 (" + cx + "," + cz + ")（stateId=" + stateId
                + "，y=64~159 随机）· " + (RewindEngine.getInstance().running()
                ? "倒流恢复源" : "记录窗口（下次倒流）") + " 生效";
    }

    /**
     * 空中"字画"：用点阵字体把 text 逐像素写成虚假 diff（每个像素 = 一条记录，stateId=指定方块）。
     * 倒流时这些格逐格"凭空浮现"，形成空中文字/图案（可视化倒流测试）。
     * <p>
     * 若传入 level（命令层），注入的同时<b>立即放置方块</b>——字画当场可见；
     * diff 记录仍保留，倒流恢复时目标已是该方块则跳过（字画保持）。
     *
     * @param x0/y0/z0 文字左上角起始坐标（y 建议 100+，抬头可见）
     * @param text     内容（A-Z/0-9/部分符号；小写自动转大写）
     * @param stateId  方块状态 id（默认 1=石头）
     * @param scale    放大倍数（1=5x5，2=10x10，…）
     * @return 描述文本
     */
    public static String fakeDiffText(int x0, int y0, int z0, String text, int stateId, int scale) {
        return fakeDiffText(null, x0, y0, z0, text, stateId, scale);
    }

    /** 同 {@link #fakeDiffText(int, int, int, String, int, int)}，但 level 非空时立即放置字画方块 */
    public static String fakeDiffText(ServerLevel level, int x0, int y0, int z0, String text,
                                      int stateId, int scale) {
        DiffStore target = fakeTarget(level);
        if (target == null) {
            return "差异库未初始化（先 /samsara debug flow setup + flow rewind）";
        }
        if (scale < 1 || scale > 8) {
            return "scale 须在 1~8";
        }
        if (text == null || text.isEmpty()) {
            return "文字为空";
        }
        // 总量上限：防止超大字画（长文本 × scale²）一次注入数万条阻塞服务器主线程
        int maxPixels = 50_000;
        long estimated = (long) text.length() * 25 * scale * scale;
        if (estimated > maxPixels) {
            return "字画规模过大（约 " + estimated + " 格 > 上限 " + maxPixels
                    + "）：请缩短文字或调小 scale（当前 " + text.length() + " 字符 × scale=" + scale + "）";
        }
        long now = System.currentTimeMillis();
        int idx = 0;
        int skipped = 0;
        StringBuilder skippedChars = new StringBuilder();
        int cursorX = 0; // 相对起点 x 偏移
        for (char ch : text.toUpperCase().toCharArray()) {
            int fi = ch - ' ';
            int[] glyph = (fi >= 0 && fi < FONT_5X7.length) ? FONT_5X7[fi] : null;
            if (glyph == null) {
                skipped++;
                if (skippedChars.length() < 12) {
                    skippedChars.append(ch);
                }
                continue; // 不支持的字符（含中文/emoji）跳过，避免数组越界崩溃
            }
            // 字库为行优先：每个元素 = 一行，低 5 位 = 5 列（bit0=最左列，行0=顶部）。
            // 之前按"列优先"读（元素当列、位当行）导致字形转置 90° 躺下，已修正。
            for (int row = 0; row < glyph.length; row++) {
                int bits = glyph[row];
                for (int col = 0; col < 5; col++) {
                    if ((bits & (1 << col)) != 0) {
                        for (int sy = 0; sy < scale; sy++) {
                            for (int sx = 0; sx < scale; sx++) {
                                int x = x0 + (cursorX + col) * scale + sx;
                                int y = y0 - row * scale - sy; // 行0=顶，向下画（y 减小）
                                int z = z0;
                                target.record(x, y, z, stateId, now - idx);
                                if (level != null) {
                                    // 立即放置方块使字画当场可见；暂停记录避免"放置被记成新变化"。
                                    // 强制加载区块（getChunk 会加载/生成），保证即使远离玩家也能放置。
                                    boolean was = enabled;
                                    enabled = false;
                                    try {
                                        net.minecraft.core.BlockPos bp = new net.minecraft.core.BlockPos(x, y, z);
                                        level.getChunk(x >> 4, z >> 4);
                                        level.setBlock(bp, net.minecraft.world.level.block.Block.stateById(stateId), 3);
                                    } finally {
                                        enabled = was;
                                    }
                                }
                                idx++;
                            }
                        }
                    }
                }
            }
            cursorX += 6; // 字符间距：5 宽 + 1 空格
        }
        target.flush(); // 批量落盘，确保立即可读（倒流恢复读文件）
        if (idx == 0) {
            return "文字不含可绘制字符（仅支持 A-Z/0-9/部分符号）";
        }
        refreshRestoreAfterFake();
        String skipNote = skipped > 0
                ? "（已跳过 " + skipped + " 个不支持字符「" + skippedChars + "」，仅支持 A-Z/0-9/部分符号）"
                : "";
        return "已注入 " + idx + " 条虚假 diff（空中字画 " + text.toUpperCase() + skipNote + "，stateId=" + stateId
                + "，scale=" + scale + "）· 起点 (" + x0 + "," + y0 + "," + z0 + ") · 倒流时逐格浮现";
    }

    /**
     * 点阵字体（行优先）：下标 = char - ' '（32），每字符 5 行，每行低 5 位 = 5 列（bit0=最左列，行0=顶部）。
     * <p>
     * <b>点阵尺寸为 5 宽 × 5 高</b>（每个字形 {int[5]}，渲染端也按 5 行 5 列采样，见
     * {@code ClaimParticleText.drawScaled}）。字段名 {@code FONT_5X7} 是历史遗留的误名
     * （"7" 并非点阵高度），为兼容既有引用保留未改名。
     */
    public static final int[][] FONT_5X7 = new int[95][];
    static {
        // 空格
        FONT_5X7[' ' - ' '] = new int[]{0, 0, 0, 0, 0};
        // !  .  #  $  %  &  '  (  )  *  +  ,  -  .  /
        FONT_5X7['!' - ' '] = new int[]{0x04, 0x04, 0x04, 0x00, 0x04};
        FONT_5X7['"' - ' '] = new int[]{0x0A, 0x0A, 0x00, 0x00, 0x00};
        FONT_5X7['#' - ' '] = new int[]{0x0A, 0x1F, 0x0A, 0x1F, 0x0A};
        FONT_5X7['$' - ' '] = new int[]{0x04, 0x0E, 0x05, 0x0E, 0x04};
        FONT_5X7['%' - ' '] = new int[]{0x12, 0x08, 0x04, 0x02, 0x09};
        FONT_5X7['&' - ' '] = new int[]{0x06, 0x09, 0x06, 0x0A, 0x05};
        FONT_5X7['\'' - ' '] = new int[]{0x04, 0x04, 0x00, 0x00, 0x00};
        FONT_5X7['(' - ' '] = new int[]{0x08, 0x04, 0x04, 0x04, 0x08};
        FONT_5X7[')' - ' '] = new int[]{0x02, 0x04, 0x04, 0x04, 0x02};
        FONT_5X7['*' - ' '] = new int[]{0x04, 0x15, 0x0E, 0x15, 0x04};
        FONT_5X7['+' - ' '] = new int[]{0x00, 0x04, 0x0E, 0x04, 0x00};
        FONT_5X7[',' - ' '] = new int[]{0x00, 0x00, 0x00, 0x02, 0x02};
        FONT_5X7['-' - ' '] = new int[]{0x00, 0x00, 0x0E, 0x00, 0x00};
        FONT_5X7['.' - ' '] = new int[]{0x00, 0x00, 0x00, 0x00, 0x02};
        FONT_5X7['/' - ' '] = new int[]{0x10, 0x08, 0x04, 0x02, 0x01};
        // 0-9
        FONT_5X7['0' - ' '] = new int[]{0x0E, 0x11, 0x11, 0x11, 0x0E};
        FONT_5X7['1' - ' '] = new int[]{0x04, 0x06, 0x04, 0x04, 0x0E};
        FONT_5X7['2' - ' '] = new int[]{0x0E, 0x10, 0x0E, 0x01, 0x1F};
        FONT_5X7['3' - ' '] = new int[]{0x1F, 0x08, 0x0E, 0x08, 0x0F}; // 简化
        FONT_5X7['4' - ' '] = new int[]{0x08, 0x0C, 0x0A, 0x1F, 0x08};
        FONT_5X7['5' - ' '] = new int[]{0x1F, 0x01, 0x0E, 0x10, 0x0E};
        FONT_5X7['6' - ' '] = new int[]{0x0E, 0x01, 0x0F, 0x11, 0x0E};
        FONT_5X7['7' - ' '] = new int[]{0x1F, 0x10, 0x08, 0x04, 0x04};
        FONT_5X7['8' - ' '] = new int[]{0x0E, 0x11, 0x0E, 0x11, 0x0E};
        FONT_5X7['9' - ' '] = new int[]{0x0E, 0x11, 0x1E, 0x10, 0x0E};
        // : ; < = > ? @
        FONT_5X7[':' - ' '] = new int[]{0x00, 0x02, 0x00, 0x02, 0x00};
        FONT_5X7[';' - ' '] = new int[]{0x00, 0x02, 0x00, 0x02, 0x02};
        FONT_5X7['<' - ' '] = new int[]{0x08, 0x04, 0x02, 0x04, 0x08};
        FONT_5X7['=' - ' '] = new int[]{0x00, 0x0E, 0x00, 0x0E, 0x00};
        FONT_5X7['>' - ' '] = new int[]{0x02, 0x04, 0x08, 0x04, 0x02};
        FONT_5X7['?' - ' '] = new int[]{0x0E, 0x10, 0x08, 0x00, 0x04};
        FONT_5X7['@' - ' '] = new int[]{0x0E, 0x11, 0x17, 0x01, 0x0E};
        // A-Z
        FONT_5X7['A' - ' '] = new int[]{0x0E, 0x11, 0x1F, 0x11, 0x11};
        FONT_5X7['B' - ' '] = new int[]{0x0F, 0x11, 0x0F, 0x11, 0x0F};
        FONT_5X7['C' - ' '] = new int[]{0x0E, 0x11, 0x01, 0x11, 0x0E};
        FONT_5X7['D' - ' '] = new int[]{0x0F, 0x11, 0x11, 0x11, 0x0F};
        FONT_5X7['E' - ' '] = new int[]{0x1F, 0x01, 0x0F, 0x01, 0x1F};
        FONT_5X7['F' - ' '] = new int[]{0x1F, 0x01, 0x0F, 0x01, 0x01};
        FONT_5X7['G' - ' '] = new int[]{0x0E, 0x11, 0x19, 0x11, 0x0E};
        FONT_5X7['H' - ' '] = new int[]{0x11, 0x11, 0x1F, 0x11, 0x11};
        FONT_5X7['I' - ' '] = new int[]{0x0E, 0x04, 0x04, 0x04, 0x0E};
        FONT_5X7['J' - ' '] = new int[]{0x1C, 0x08, 0x08, 0x09, 0x06};
        FONT_5X7['K' - ' '] = new int[]{0x11, 0x09, 0x07, 0x09, 0x11};
        FONT_5X7['L' - ' '] = new int[]{0x01, 0x01, 0x01, 0x01, 0x1F};
        FONT_5X7['M' - ' '] = new int[]{0x11, 0x1B, 0x15, 0x11, 0x11};
        FONT_5X7['N' - ' '] = new int[]{0x11, 0x13, 0x15, 0x19, 0x11};
        FONT_5X7['O' - ' '] = new int[]{0x0E, 0x11, 0x11, 0x11, 0x0E};
        FONT_5X7['P' - ' '] = new int[]{0x0F, 0x11, 0x0F, 0x01, 0x01};
        FONT_5X7['Q' - ' '] = new int[]{0x0E, 0x11, 0x15, 0x09, 0x16};
        FONT_5X7['R' - ' '] = new int[]{0x0F, 0x11, 0x0F, 0x09, 0x11};
        FONT_5X7['S' - ' '] = new int[]{0x0E, 0x01, 0x0E, 0x10, 0x0E};
        FONT_5X7['T' - ' '] = new int[]{0x1F, 0x04, 0x04, 0x04, 0x04};
        FONT_5X7['U' - ' '] = new int[]{0x11, 0x11, 0x11, 0x11, 0x0E};
        FONT_5X7['V' - ' '] = new int[]{0x11, 0x11, 0x11, 0x0A, 0x04};
        FONT_5X7['W' - ' '] = new int[]{0x11, 0x11, 0x15, 0x1B, 0x11};
        FONT_5X7['X' - ' '] = new int[]{0x11, 0x0A, 0x04, 0x0A, 0x11};
        FONT_5X7['Y' - ' '] = new int[]{0x11, 0x0A, 0x04, 0x04, 0x04};
        FONT_5X7['Z' - ' '] = new int[]{0x1F, 0x08, 0x04, 0x02, 0x1F};
        // [ \ ] ^ _ `
        FONT_5X7['[' - ' '] = new int[]{0x0E, 0x02, 0x02, 0x02, 0x0E};
        FONT_5X7['\\' - ' '] = new int[]{0x01, 0x02, 0x04, 0x08, 0x10};
        FONT_5X7[']' - ' '] = new int[]{0x0E, 0x08, 0x08, 0x08, 0x0E};
        FONT_5X7['^' - ' '] = new int[]{0x04, 0x0A, 0x00, 0x00, 0x00};
        FONT_5X7['_' - ' '] = new int[]{0x00, 0x00, 0x00, 0x00, 0x1F};
        FONT_5X7['`' - ' '] = new int[]{0x02, 0x04, 0x00, 0x00, 0x00};
        // 其他常用
        FONT_5X7['~' - ' '] = new int[]{0x0A, 0x15, 0x00, 0x00, 0x00};
    }
}
