package com.shenqianye.samsara.track;

import com.shenqianye.samsara.Samsara;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 回放/回看的客户端假载具（F-164 之后的 Track v2 收尾）。
 * <p>
 * <b>不创建服务端实体</b>：只给附近的观看者发送
 * {@link ClientboundAddEntityPacket} / metadata / passengers / teleport，
 * 因此 {@code /execute as @e[type=...]} 查不到任何残留，也不会被交互、推动或掉落。
 * 停止/断线/切维度/人偶消失时发送移除包并清理。
 * <p>
 * 只支持原版载具类型（船/筏/矿车/马类/猪/炽足兽/骆驼/羊驼）；未知类型返回
 * {@link #supported(String)} = false，由人偶回退到蹲姿近似。
 */
public final class FakeVehicle {
    private static final AtomicInteger IDS = new AtomicInteger(1_800_000_000);
    static final double VIEW_RANGE = 128.0;
    /** 载具固定水平姿态：永远不跟随人偶俯仰，避免船/矿车/马被“掀斜”。 */
    static final float VEHICLE_PITCH = 0.0f;

    private final int id = IDS.getAndIncrement();
    private final UUID uuid = new UUID(0x5A4D53415241L, id);
    private final Set<UUID> sentTo = new HashSet<>();
    private String typeId = "";
    private EntityType<?> type;
    private Entity shell;
    private boolean broken;

    /** 当前是否已成功解析到可渲染的载具类型。 */
    public boolean active() {
        return type != null && !broken;
    }

    public String typeId() {
        return typeId;
    }

    /** 由人偶的 VEHICLE 事件驱动；空串 = 收起载具。 */
    public void setType(String entityTypeId) {
        String value = entityTypeId == null ? "" : entityTypeId.trim();
        if (value.equals(typeId)) {
            return;
        }
        remove();
        typeId = value;
        type = null;
        shell = null;
        broken = false;
        if (value.isEmpty()) {
            return;
        }
        type = resolve(value);
        if (type == null) {
            broken = true;
            Samsara.LOGGER.debug("[回放] 不支持的载具类型 {}，回退蹲姿", value);
        }
    }

    /** 每 tick 跟随人偶位置；只给同维度、视距内的玩家发假实体包。 */
    public void tick(ServerLevel level, TrackMannequin mannequin) {
        if (!active()) {
            remove();
            return;
        }
        if (shell == null) {
            try {
                shell = type.create(level, EntitySpawnReason.LOAD);
            } catch (RuntimeException e) {
                shell = null;
            }
            if (shell == null) {
                broken = true;
                remove();
                return;
            }
            shell.setId(id);
            shell.setUUID(uuid);
        }
        List<ServerPlayer> nearby = nearby(level, mannequin);
        Set<UUID> now = new HashSet<>();
        for (ServerPlayer player : nearby) {
            now.add(player.getUUID());
            if (sentTo.add(player.getUUID())) {
                sendAdd(player, mannequin);
                sendPassengers(player, mannequin);
            }
            sendTeleport(player, mannequin);
        }
        for (UUID old : new ArrayList<>(sentTo)) {
            if (!now.contains(old)) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(old);
                if (player != null) {
                    player.connection.send(new ClientboundRemoveEntitiesPacket(id));
                }
                sentTo.remove(old);
            }
        }
    }

    private List<ServerPlayer> nearby(ServerLevel level, TrackMannequin mannequin) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level) {
                continue;
            }
            if (player.distanceToSqr(mannequin) <= VIEW_RANGE * VIEW_RANGE) {
                out.add(player);
            }
        }
        return out;
    }

    private void sendAdd(ServerPlayer player, TrackMannequin mannequin) {
        try {
            shell.setPos(mannequin.getX(), mannequin.getY(), mannequin.getZ());
            shell.setYRot(mannequin.getYRot());
            // 载具只跟随水平朝向：人偶的俯仰不能把船/矿车/马一起掀斜
            shell.setXRot(VEHICLE_PITCH);
            shell.setYHeadRot(mannequin.getYHeadRot());
            player.connection.send(new ClientboundAddEntityPacket(
                    id, uuid, mannequin.getX(), mannequin.getY(), mannequin.getZ(),
                    VEHICLE_PITCH, mannequin.getYRot(), type, 0, Vec3.ZERO,
                    mannequin.getYHeadRot()));
            var data = shell.getEntityData().getNonDefaultValues();
            if (!data.isEmpty()) {
                player.connection.send(new ClientboundSetEntityDataPacket(id, data));
            }
        } catch (RuntimeException e) {
            Samsara.LOGGER.debug("[回放] 发送载具实体失败: {}", e.getMessage());
        }
    }

    private void sendPassengers(ServerPlayer player, TrackMannequin mannequin) {
        if (mannequin.isPassenger()) {
            return;
        }
        try {
            if (mannequin.startRiding(shell, true, true)) {
                ClientboundSetPassengersPacket packet = new ClientboundSetPassengersPacket(shell);
                mannequin.stopRiding();
                player.connection.send(packet);
            }
        } catch (RuntimeException e) {
            if (mannequin.isPassenger()) {
                mannequin.stopRiding();
            }
            Samsara.LOGGER.debug("[回放] 发送载具乘客失败: {}", e.getMessage());
        }
    }

    private void sendTeleport(ServerPlayer player, TrackMannequin mannequin) {
        try {
            player.connection.send(new ClientboundTeleportEntityPacket(id,
                    new PositionMoveRotation(
                            new Vec3(mannequin.getX(), mannequin.getY(), mannequin.getZ()),
                            Vec3.ZERO, mannequin.getYRot(), VEHICLE_PITCH),
                    Set.of(), mannequin.onGround()));
        } catch (RuntimeException ignored) {
            // 玩家已离线/连接关闭：下一次 tick 会从 sentTo 中清掉
        }
    }

    /** 收起载具：给所有收到过假实体的玩家发移除包；可重复调用。 */
    public void remove() {
        for (UUID viewer : new ArrayList<>(sentTo)) {
            ServerPlayer player = shell == null || shell.level().getServer() == null ? null
                    : shell.level().getServer().getPlayerList().getPlayer(viewer);
            if (player != null) {
                try {
                    player.connection.send(new ClientboundRemoveEntitiesPacket(id));
                } catch (RuntimeException ignored) {
                    // 离线
                }
            }
        }
        sentTo.clear();
        shell = null;
        type = null;
        broken = false;
    }

    private static EntityType<?> resolve(String id) {
        try {
            EntityType<?> found = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id));
            return found != null && vehicleLike(EntityType.getKey(found).getPath()) ? found : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 只允许载具类实体；未知/玩家/生物坐骑以外的类型回退蹲姿。 */
    private static boolean vehicleLike(String path) {
        return vehicleLikePath(path);
    }

    /** 纯类型判定（单测用）；输入是 registry path（如 {@code oak_boat}）。 */
    static boolean vehicleLikePath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.toLowerCase(java.util.Locale.ROOT);
        return p.contains("boat") || p.contains("raft") || p.contains("minecart")
                || p.contains("horse") || p.contains("llama") || p.contains("camel")
                || p.equals("donkey") || p.equals("mule")
                || p.equals("pig") || p.equals("strider");
    }

    public static boolean supported(String entityTypeId) {
        return entityTypeId != null && !entityTypeId.isBlank() && resolve(entityTypeId) != null;
    }
}
