package com.shenqianye.samsara.rewind;

import com.mojang.math.Transformation;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.CommandConfig;
import com.shenqianye.samsara.season.Season;
import com.shenqianye.samsara.season.SeasonManager;
import com.shenqianye.samsara.track.ChatLogStore;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 倒流全息留言（F-110）。
 *
 * <p>只做“发言位置 + 时间”的展示层：不参与方块恢复，不读写 diff，不写存档。
 * 用 {@link Display.TextDisplay} 放在发言坐标上方；只在附近玩家且区块已加载时生成，
 * 绝不为留言调用 {@code getChunk}/加 ticket。
 *
 * <p>虚拟时间按权威进度 A 反向推进：
 * {@code tVirtual = seasonStart + (seasonEnd - seasonStart) * (1 - A)}。
 * 当前可见窗口为 {@code [tVirtual, tVirtual + rewind.chatWindowMs]}，
 * 因此倒流从新到旧推进时留言按时间倒序浮现，并在离开窗口后消失（缩放渐变）。
 */
public final class RewindChatHolograms {
    private static final Map<String, Display.TextDisplay> DISPLAYS = new HashMap<>();
    private static List<ChatLogStore.Entry> entries = List.of();
    private static int loadedSeason = -1;
    private static long loadedStart = Long.MIN_VALUE;
    private static long loadedEnd = Long.MIN_VALUE;
    private static long lastTickMs;

    private RewindChatHolograms() {
    }

    /** 每 tick 由 {@link RewindEngine} 调用；无倒流/关闭/结束时清理。 */
    public static synchronized void tick(MinecraftServer server, RewindData data) {
        if (server == null || data == null || data.teleportPhaseDone
                || !CommandConfig.getInstance().rewindChatHolograms()) {
            clear();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTickMs < 1000L) {
            return;
        }
        lastTickMs = now;
        if (!ensureLoaded(data)) {
            clear();
            return;
        }
        double a = DiffRestore.authoritativeProgress(now, data);
        double tVirtual = loadedStart + (loadedEnd - loadedStart) * (1.0 - a);
        long window = Math.max(1_000, CommandConfig.getInstance().rewindChatWindowMs());
        int max = Math.max(1, CommandConfig.getInstance().rewindChatMaxDisplays());
        int radius = Math.max(16, CommandConfig.getInstance().rewindChatRadius());
        ServerLevel level = server.overworld();
        String overworld = level.dimension().identifier().toString();
        double lower = tVirtual;
        double upper = tVirtual + window;

        List<ChatLogStore.Entry> visible = new ArrayList<>();
        for (ChatLogStore.Entry entry : entries) {
            if (!overworld.equals(entry.dimension())) {
                continue;
            }
            if (entry.timeMs() < lower || entry.timeMs() > upper) {
                continue;
            }
            visible.add(entry);
        }
        visible.sort((a1, a2) -> Long.compare(a2.timeMs(), a1.timeMs()));

        // 移除离开窗口的展示物
        Iterator<Map.Entry<String, Display.TextDisplay>> it = DISPLAYS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Display.TextDisplay> display = it.next();
            boolean keep = false;
            for (ChatLogStore.Entry entry : visible) {
                if (key(entry).equals(display.getKey())) {
                    keep = true;
                    break;
                }
            }
            if (!keep) {
                discard(display.getValue());
                it.remove();
            }
        }

        for (ChatLogStore.Entry entry : visible) {
            String key = key(entry);
            Display.TextDisplay display = DISPLAYS.get(key);
            if (display == null) {
                if (DISPLAYS.size() >= max) {
                    continue;
                }
                if (!nearPlayer(level, entry, radius)) {
                    continue;
                }
                int cx = (int) Math.floor(entry.x()) >> 4;
                int cz = (int) Math.floor(entry.z()) >> 4;
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    continue; // 不为留言加载远处区块
                }
                display = create(level, entry, radius);
                if (display == null) {
                    continue;
                }
                DISPLAYS.put(key, display);
            }
            applyFade(display, entry, lower, upper, window);
        }
    }

    private static boolean ensureLoaded(RewindData data) {
        Season season = findSeason(data.seasonId);
        long seasonStart = season != null && season.startTime != null
                ? season.startTime : data.startMs - Math.max(1, data.durationMs);
        long seasonEnd = season != null && season.endTime != null
                ? Math.min(season.endTime, data.startMs) : data.startMs;
        if (seasonEnd <= seasonStart) {
            return false;
        }
        if (loadedSeason == data.seasonId && loadedStart == seasonStart && loadedEnd == seasonEnd) {
            return true;
        }
        loadedSeason = data.seasonId;
        loadedStart = seasonStart;
        loadedEnd = seasonEnd;
        entries = ChatLogStore.readRange(data.seasonId, seasonStart, seasonEnd, null);
        Samsara.LOGGER.info("[周目管理 samsara] 倒流留言：周目 #{} 载入 {} 条聊天（{} → {}）",
                data.seasonId, entries.size(),
                com.shenqianye.samsara.track.view.ViewTime.format(seasonStart),
                com.shenqianye.samsara.track.view.ViewTime.format(seasonEnd));
        return true;
    }

    private static Season findSeason(int id) {
        for (Season season : SeasonManager.getInstance().data().seasons) {
            if (season.id == id) {
                return season;
            }
        }
        return null;
    }

    private static boolean nearPlayer(ServerLevel level, ChatLogStore.Entry entry, int radius) {
        double r2 = (double) radius * radius;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(entry.x(), entry.y(), entry.z()) <= r2) {
                return true;
            }
        }
        return false;
    }

    private static Display.TextDisplay create(ServerLevel level, ChatLogStore.Entry entry, int radius) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(
                        net.minecraft.resources.Identifier.withDefaultNamespace("text_display"))
                .map(ref -> ref.value()).orElse(null);
        if (type == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        EntityType<? extends Display.TextDisplay> textType =
                (EntityType<? extends Display.TextDisplay>) type;
        Hologram display = new Hologram(textType, level);
        display.setText(Component.literal(entry.name() + ": " + entry.message())
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)));
        display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        display.setPos(entry.x(), entry.y() + 1.6, entry.z());
        display.setNoGravity(true);
        display.setSilent(true);
        display.setViewRange(Math.max(32f, radius));
        level.addFreshEntity(display);
        return display;
    }

    /** 进入窗口时放大、离开时缩小，形成渐显/渐隐。 */
    private static void applyFade(Display.TextDisplay display, ChatLogStore.Entry entry,
                                  double lower, double upper, long window) {
        double fade = Math.max(1_000.0, Math.min(10_000.0, window / 4.0));
        double fromLower = entry.timeMs() - lower;
        double fromUpper = upper - entry.timeMs();
        double factor = Math.min(1.0, Math.min(fromLower / fade, fromUpper / fade));
        float scale = (float) (0.5 + 0.5 * Math.max(0.0, Math.min(1.0, factor)));
        display.setTransformation(new Transformation(
                new Vector3f(0, 0, 0), new Quaternionf(),
                new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    private static String key(ChatLogStore.Entry entry) {
        return entry.uuid() + "|" + entry.timeMs() + "|"
                + Integer.toHexString(entry.message().hashCode());
    }

    public static synchronized void clear() {
        for (Display.TextDisplay display : DISPLAYS.values()) {
            discard(display);
        }
        DISPLAYS.clear();
        entries = List.of();
        loadedSeason = -1;
        loadedStart = Long.MIN_VALUE;
        loadedEnd = Long.MIN_VALUE;
        lastTickMs = 0L;
    }

    private static void discard(Display.TextDisplay display) {
        try {
            if (display != null && !display.isRemoved()) {
                display.discard();
            }
        } catch (Exception ignored) {
        }
    }

    /** TextDisplay that never persists into the save (best-effort; also discarded on stop). */
    private static final class Hologram extends Display.TextDisplay {
        Hologram(EntityType<? extends Display.TextDisplay> type, ServerLevel level) {
            super(type, level);
        }

        @Override
        public boolean shouldBeSaved() {
            return false;
        }
    }
}
