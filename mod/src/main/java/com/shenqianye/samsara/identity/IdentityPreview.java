package com.shenqianye.samsara.identity;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.claim.AreaService;
import com.shenqianye.samsara.claim.ClaimNode;
import com.shenqianye.samsara.storage.StoragePaths;
import com.shenqianye.samsara.track.TrackRecorder;
import com.shenqianye.samsara.track.view.ViewTrackReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 身份组影响预览：圈地、玩家数据、轨迹、活动、在线状态（命令与 Web 共用）。 */
public final class IdentityPreview {
    private IdentityPreview() {
    }

    public static String describe(MinecraftServer server, Path identityRoot,
                                  UUID data, List<UUID> aliases) {
        StringBuilder out = new StringBuilder();
        out.append("圈地: ").append(claimCount(data)).append(" 面\n");
        boolean playerdata = identityRoot != null && data != null
                && Files.isRegularFile(identityRoot.resolve("playerdata").resolve(data + ".dat"));
        out.append("playerdata: ").append(playerdata ? "存在" : "不存在");
        if (server != null && aliases != null) {
            int worldFiles = 0;
            for (UUID alias : aliases) {
                if (Files.isRegularFile(server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
                        .resolve(alias + ".dat"))) {
                    worldFiles++;
                }
            }
            out.append(" · 当前账号数据文件 ").append(worldFiles).append(" 个");
        }
        out.append("\n");
        out.append("轨迹: ").append(trackCount(aliases)).append(" 个文件\n");
        out.append("活动: ").append(activityCount(aliases)).append(" 条（近 30 天）\n");
        out.append("在线: ").append(online(server, data) ? "是" : "否");
        return out.toString();
    }

    public static int claimCount(UUID data) {
        if (data == null) {
            return 0;
        }
        try {
            int count = 0;
            for (Map<?, ?> dim : AreaService.getInstance().getAllNodes().values()) {
                for (Object value : dim.values()) {
                    if (value instanceof ClaimNode node && node.getOwnerUUID() != null
                            && IdentityService.getInstance().sameIdentity(node.getOwnerUUID(), data)) {
                        count++;
                    }
                }
            }
            return count;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static int trackCount(List<UUID> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return 0;
        }
        try {
            TrackRecorder recorder = TrackRecorder.getInstance();
            Path root = recorder.worldRoot();
            if (root == null) {
                return 0;
            }
            int count = 0;
            for (UUID alias : aliases) {
                count += ViewTrackReader.available(root, alias, cycle -> null).size();
            }
            return count;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static int activityCount(List<UUID> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return 0;
        }
        try {
            Path dir = StoragePaths.get().world("activity");
            if (!Files.isDirectory(dir)) {
                return 0;
            }
            int count = 0;
            LocalDate today = LocalDate.now();
            for (int i = 0; i < 30; i++) {
                Path file = dir.resolve(today.minusDays(i) + ".jsonl");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                    for (String line : (Iterable<String>) lines::iterator) {
                        for (UUID alias : aliases) {
                            if (line.contains("\"" + alias + "\"")) {
                                count++;
                                break;
                            }
                        }
                    }
                }
            }
            return count;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    public static boolean online(MinecraftServer server, UUID data) {
        if (server == null || data == null) {
            return false;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (IdentityService.getInstance().identityOf(player.getUUID()).equals(data)) {
                return true;
            }
        }
        return false;
    }
}
