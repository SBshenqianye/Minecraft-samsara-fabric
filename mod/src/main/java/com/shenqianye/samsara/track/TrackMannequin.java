package com.shenqianye.samsara.track;

import com.shenqianye.samsara.Samsara;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.Level;

/**
 * 回看（{@code ViewMannequin}）与捕捉式回放（{@link ReplayMannequin}）共用的回放人偶基类。
 * <p>
 * 只新增「按轨迹 flags 应用可见姿态」这一层；位置/朝向仍由各自的回放管线
 * {@code snapTo} 驱动，实体保持无 AI、无重力、不自己移动：
 * <ul>
 *   <li>潜行：{@link Pose#CROUCHING} + shift 按键状态</li>
 *   <li>疾跑：shared flag（{@code setSprinting}），不改变姿态</li>
 *   <li>游泳：{@link Pose#SWIMMING} + swimming shared flag</li>
 *   <li>飞行：{@link Pose#FALL_FLYING} + {@code FLAG_FALL_FLYING} shared flag
 *       （26.3 客户端要 shared flag 才会摆出鞘翅/平飞姿态）</li>
 *   <li>骑乘：{@link Pose#CROUCHING} 作为「坐姿」可见近似——26.3 的
 *       {@code Mannequin} 只接受 STANDING/CROUCHING/SWIMMING/FALL_FLYING/SLEEPING，
 *       {@code Pose.SITTING} 不在人偶姿态表里、客户端也没有对应渲染分支；
 *       真实载具类型（马/船/矿车）需要 track v2 记录，届时再还原具体坐骑</li>
 *   <li>受伤：只续客户端受击闪烁（damage event），不进入 {@code hurtServer}，不扣血/击退</li>
 *   <li>死亡：{@link Pose#SLEEPING} 表现倒地；不修改 health、不移除实体</li>
 * </ul>
 * flags 缺失或为 0 时回到站立，旧 v1 文件行为不变。
 */
public abstract class TrackMannequin extends Mannequin {
    /** 上一次受击闪烁广播的 level game time；用于每 10 tick 续一次（不依赖实体自身 tick）。 */
    private long lastHurtBroadcastTick = Long.MIN_VALUE;
    /** 骑乘近似只提示一次，避免每个回放/每 tick 刷日志。 */
    private static boolean ridingApproximationLogged;

    protected TrackMannequin(Level level) {
        super(level);
    }

    /**
     * 按当前轨迹点的 flags 应用可见姿态。回放/回看管线每 tick 调用一次；
     * 同一个 flags 重复调用是幂等的（受伤闪烁按 level game time 每 10 tick 续一次）。
     */
    public final void applyTrackFlags(int flags) {
        TrackPose.State state = TrackPose.of(flags);
        setShiftKeyDown(state.sneak());
        setSprinting(state.sprint());
        setSwimming(state.swimming());
        // 26.3：Pose 只影响尺寸/视线高度，鞘翅姿态还要 shared flag 才会同步给客户端
        setSharedFlag(FLAG_FALL_FLYING, state.flying());
        setPose(poseOf(state.posture()));
        if (state.posture() == TrackPose.Posture.RIDING && !ridingApproximationLogged) {
            ridingApproximationLogged = true;
            Samsara.LOGGER.info("[回放] 骑乘姿态暂用蹲姿近似；真实载具类型需要 track v2 记录");
        }
        applyHurt(state.hurt());
    }

    /**
     * 回放人偶没有真实鞘翅装备。原版 {@code updateFallFlying()} 会在
     * {@code canGlide() == false} 时清掉 fall-flying shared flag，导致客户端
     * 收不到 FLYING；而强行让 {@code canGlide()} 返回 true 又会进入原版
     * “按装备栏随机损坏滑翔物”的分支（空装备列表会抛异常）。
     * 回放的飞行姿态完全由轨迹 flags 驱动，这里直接跳过原版维护逻辑。
     */
    @Override
    protected void updateFallFlying() {
    }

    private static Pose poseOf(TrackPose.Posture posture) {
        return switch (posture) {
            case DEAD -> Pose.SLEEPING;
            case RIDING -> Pose.CROUCHING;
            case FLYING -> Pose.FALL_FLYING;
            case SWIMMING -> Pose.SWIMMING;
            case SNEAK -> Pose.CROUCHING;
            case STANDING -> Pose.STANDING;
        };
    }

    /**
     * 受击闪烁：轨迹里的 HURT 是离散状态（原玩家 hurtTime &gt; 0），回放时按
     * level game time 每 10 tick 续一次，不依赖实体自身是否在 entity-ticking 区块里。
     * 只发客户端表现包（{@code ClientboundDamageEventPacket}），不触发
     * {@code hurtServer}，因此不会扣血、击退或捕捉。
     */
    private void applyHurt(boolean hurt) {
        if (!hurt) {
            lastHurtBroadcastTick = Long.MIN_VALUE;
            return;
        }
        long now = level().getGameTime();
        if (lastHurtBroadcastTick != Long.MIN_VALUE && now - lastHurtBroadcastTick < 10L) {
            return;
        }
        lastHurtBroadcastTick = now;
        animateHurt(getYRot());
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.broadcastDamageEvent(this, damageSources().generic());
        }
    }
}
