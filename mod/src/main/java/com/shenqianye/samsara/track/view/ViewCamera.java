package com.shenqianye.samsara.track.view;

import com.shenqianye.samsara.config.CommandConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/** 回看镜头：自动跟随、一键对齐、场景中心对齐与自动瞄准。 */
final class ViewCamera {

    private ViewCamera() {
    }

    /** 跟随观察点：人偶身后 4.5 格、上方 2 格，并面向人偶（不是贴在人偶身体里）。 */
    private static final double FOLLOW_DISTANCE = 4.5;
    private static final double FOLLOW_HEIGHT = 2.0;

    /**
     * 自由活动 + 自动跟随：
     * 观看者始终旁观，可在 {@code view.freeRoamRadius} 范围内自由飞行；
     * 超出范围、人偶瞬移或换维度时才重新贴近。
     */
    static void follow(ViewSession s, ServerPlayer player, long trackTime) {
        ViewTarget target = followTargetOf(s, null);
        if (target == null || !target.visible || target.cursor == null) {
            return;
        }
        ViewCursor.Point point = target.cursor.pointAt(trackTime);
        ServerLevel level = ViewManager.findLevel(player.level().getServer(), point.dimension());
        if (level == null) {
            return;
        }
        int radius = Math.max(8, CommandConfig.getInstance().viewFreeRoamRadius());
        boolean dimensionChanged = !point.dimension().equals(s.lastFollowDim);
        boolean tooFar = player.distanceToSqr(point.x(), point.y(), point.z())
                > (double) radius * radius;
        if (s.followInitialized && !dimensionChanged && !target.teleported && !tooFar) {
            return; // 自由活动范围内：不打断观看者
        }
        snapToObservation(s, player, level, point);
        target.teleported = false;
    }

    /**
     * 一键对齐（F-96）：单目标贴到理想观察点；多目标贴到场景中心；
     * 有 --follow 时按该目标。不改变 follow 开关。
     */
    static void align(ViewSession s, ServerPlayer player, long trackTime) {
        ViewTarget target = followTargetOf(s, null);
        if (target != null && target.cursor != null) {
            ViewCursor.Point point = target.cursor.pointAt(trackTime);
            ServerLevel level = ViewManager.findLevel(player.level().getServer(), point.dimension());
            if (level != null) {
                snapToObservation(s, player, level, point);
                target.teleported = false;
                return;
            }
        }
        if (s.group) {
            snapToScene(s, player, trackTime);
        }
    }

    /** 有 --follow 目标优先；否则单目标取 primary，多目标返回 null（走场景中心）。 */
    static ViewTarget followTargetOf(ViewSession s, List<ViewTarget> visible) {
        if (s.followTarget != null) {
            for (ViewTarget target : s.targets) {
                if (target.uuid.equals(s.followTarget)
                        && (visible == null || visible.contains(target))) {
                    return target;
                }
            }
            return null;
        }
        if (!s.group) {
            return s.primary;
        }
        return null;
    }

    /** 多目标一键对齐：场景中心上方/后方，面向质心。 */
    static void snapToScene(ViewSession s, ServerPlayer player, long trackTime) {
        double cx = 0;
        double cy = 0;
        double cz = 0;
        int count = 0;
        for (ViewTarget target : s.targets) {
            if (!target.visible || target.cursor == null) {
                continue;
            }
            ViewCursor.Point point = target.cursor.pointAt(trackTime);
            if (s.activeDimension != null && !point.dimension().equals(s.activeDimension)) {
                continue;
            }
            cx += point.x();
            cy += point.y();
            cz += point.z();
            count++;
        }
        if (count == 0) {
            if (s.primary != null && s.primary.cursor != null) {
                ViewCursor.Point point = s.primary.cursor.pointAt(trackTime);
                ServerLevel level = ViewManager.findLevel(player.level().getServer(), point.dimension());
                if (level != null) {
                    snapToObservation(s, player, level, point);
                }
            }
            return;
        }
        cx /= count;
        cy /= count;
        cz /= count;
        ServerLevel level = ViewManager.findLevel(player.level().getServer(), s.activeDimension);
        if (level == null) {
            return;
        }
        double vx = cx;
        double vy = cy + 10.0;
        double vz = cz + 12.0;
        double dx = cx - vx;
        double dz = cz - vz;
        double dy = (cy + 1.2) - (vy + 1.62);
        float lookYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float lookPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.teleportTo(level, vx, vy, vz, Set.of(), lookYaw, lookPitch, false);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        s.followInitialized = true;
        s.mannequinTeleported = false;
        s.lastFollowDim = s.activeDimension;
        s.lastWriteDim = s.activeDimension;
        s.lastWriteX = vx;
        s.lastWriteY = vy;
        s.lastWriteZ = vz;
        s.lastWriteAtMs = System.currentTimeMillis();
        s.lastAimAtMs = System.currentTimeMillis();
        if (s.aimTracker != null) {
            s.aimTracker.reset();
        }
    }

    static void snapToObservation(ViewSession s, ServerPlayer player,
                                   ServerLevel level, ViewCursor.Point point) {
        double yawRad = Math.toRadians(point.yaw());
        double vx = point.x() + Math.sin(yawRad) * FOLLOW_DISTANCE;
        double vy = point.y() + FOLLOW_HEIGHT;
        double vz = point.z() - Math.cos(yawRad) * FOLLOW_DISTANCE;
        double dx = point.x() - vx;
        double dz = point.z() - vz;
        double dy = (point.y() + 1.2) - (vy + 1.62);
        float lookYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float lookPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.teleportTo(level, vx, vy, vz, Set.of(), lookYaw, lookPitch, false);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        s.followInitialized = true;
        s.mannequinTeleported = false;
        s.lastFollowDim = point.dimension();
        s.lastWriteDim = point.dimension();
        s.lastWriteX = vx;
        s.lastWriteY = vy;
        s.lastWriteZ = vz;
        s.lastWriteAtMs = System.currentTimeMillis();
        s.lastAimAtMs = System.currentTimeMillis();
        if (s.aimTracker != null) {
            s.aimTracker.reset();
        }
    }

    /**
     * 自动瞄准（F-100）：只发旋转增量，不改位置/速度。
     * 单目标瞄该目标；群组瞄 --follow 目标，否则瞄视线中心最近且在 edge 内的人偶。
     */
    static void applyAim(ViewSession s, ServerPlayer player, long trackTime,
                          List<ViewTarget> visible) {
        if (player.getCamera() != player) {
            return;
        }
        ViewTarget chosen = aimTargetOf(s, player, trackTime, visible);
        if (chosen == null || chosen.cursor == null) {
            return;
        }
        ViewCursor.Point point = chosen.cursor.pointAt(trackTime);
        if (!point.dimension().equals(player.level().dimension().identifier().toString())) {
            return;
        }
        double targetX = point.x();
        double targetY = point.y() + 1.5;
        double targetZ = point.z();
        if (player.distanceToSqr(targetX, targetY, targetZ) > 128.0 * 128.0) {
            return;
        }
        long now = System.currentTimeMillis();
        double dt = Math.min(0.25, (now - s.lastAimAtMs) / 1000.0);
        s.lastAimAtMs = now;
        if (dt <= 0) {
            return;
        }
        ViewAim.Delta delta;
        try {
            if (s.aimSnap) {
                // 2~5Hz 快速对准：只在节流窗口到点时一次性转到位，避免缓慢拖拽头晕。
                s.aimSnapAccumSec += dt;
                double interval = 1.0 / Math.max(2, s.aimHz);
                if (s.aimSnapAccumSec < interval) {
                    return;
                }
                s.aimSnapAccumSec = 0.0;
                delta = ViewAim.snap(player.getYRot(), player.getXRot(),
                        player.getX(), player.getEyeY(), player.getZ(),
                        targetX, targetY, targetZ, s.aimPitch);
            } else {
                if (s.aimTracker == null) {
                    s.aimTracker = new ViewAim.Tracker(
                            new ViewAim.Params(s.aimDeadzone, s.aimEdge, s.aimSpeed, s.aimPitch));
                }
                delta = s.aimTracker.step(player.getYRot(), player.getXRot(),
                        player.getX(), player.getEyeY(), player.getZ(),
                        targetX, targetY, targetZ, dt);
            }
        } catch (IllegalArgumentException e) {
            s.aimEnabled = false;
            return;
        }
        if (delta.isZero()) {
            return;
        }
        float pitch = player.getXRot() + delta.pitch();
        if (pitch > 90f) {
            delta = new ViewAim.Delta(delta.yaw(), 90f - player.getXRot());
        } else if (pitch < -90f) {
            delta = new ViewAim.Delta(delta.yaw(), -90f - player.getXRot());
        }
        PositionMoveRotation change = new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO,
                delta.yaw(), delta.pitch());
        player.connection.teleport(change, Set.of(
                Relative.X, Relative.Y, Relative.Z,
                Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z,
                Relative.Y_ROT, Relative.X_ROT));
    }

    static ViewTarget aimTargetOf(ViewSession s, ServerPlayer player, long trackTime,
                                          List<ViewTarget> visible) {
        ViewTarget follow = followTargetOf(s, visible);
        if (follow != null) {
            return follow;
        }
        ViewTarget best = null;
        double bestAngle = Double.MAX_VALUE;
        for (ViewTarget target : visible) {
            if (!target.visible || target.cursor == null) {
                continue;
            }
            ViewCursor.Point point = target.cursor.pointAt(trackTime);
            if (!point.dimension().equals(player.level().dimension().identifier().toString())) {
                continue;
            }
            double angle = viewAngleTo(player, point.x(), point.y() + 1.5, point.z());
            if (angle > s.aimEdge) {
                continue;
            }
            if (angle < bestAngle) {
                bestAngle = angle;
                best = target;
            }
        }
        return best;
    }

    /** 粗略视角角误差（度），用于在群组里挑选离视线中心最近的人偶。 */
    static double viewAngleTo(ServerPlayer player, double x, double y, double z) {
        double dx = x - player.getX();
        double dy = y - player.getEyeY();
        double dz = z - player.getZ();
        double horizontal = Math.hypot(dx, dz);
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double targetPitch = -Math.toDegrees(Math.atan2(dy, horizontal));
        double dyaw = ((targetYaw - player.getYRot() + 540) % 360) - 180;
        double dpitch = targetPitch - player.getXRot();
        return Math.hypot(dyaw, dpitch);
    }
}

