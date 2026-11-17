package com.shenqianye.samsara.track.view;

import com.mojang.authlib.GameProfile;
import com.shenqianye.samsara.config.CommandConfig;
import com.shenqianye.samsara.track.TrackMannequin;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;

import java.util.UUID;

/**
 * 回看幽灵人偶（设计〈幽灵人偶〉〈交互边界与旁观观察〉/ F-94、F-95）。
 * <p>
 * 独立于捕捉式 {@code ReplayMannequin}：没有捕捉回调、没有 cutoffs 写入、不写存档。
 * {@code shouldBeSaved()=false} + 会话结束 {@code discard()}，异常/停服也统一清理。
 * <p>
 * <b>回看始终不可交互</b>：{@code noPhysics}、零碰撞、不可攻击/推动/右键、不触发元件。
 * {@link #isPickable()} 返回 true，仅用于旁观者左键把相机切到人偶（原版 spectator
 * 相机接管），不是世界交互。
 * <p>
 * 可见姿态与捕捉式回放共用 {@link TrackMannequin#applyTrackFlags(int)}：
 * 每 tick 按当前轨迹点的 flags 应用潜行/疾跑/游泳/飞行/骑乘/受伤/死亡，
 * 位置与朝向仍由 {@code ViewManager} 驱动。
 */
public class ViewMannequin extends TrackMannequin {
    private final UUID sourceUuid;
    private final boolean nameTag;
    /** 只有登记过的观看者能收到这个实体；其他人完全不可见（见 {@link #broadcastToPlayer}）。 */
    private final ViewAudience audience = new ViewAudience();

    public ViewMannequin(Level level, UUID sourceUuid, String name, boolean glow, boolean nameTag) {
        super(level);
        this.sourceUuid = sourceUuid;
        this.nameTag = nameTag;
        setNoGravity(true);
        this.noPhysics = true;
        setSilent(true);
        setCustomName(Component.literal("[回看] " + name));
        setCustomNameVisible(nameTag);
        setGlowingTag(glow);
    }

    /**
     * 回看是个人摄像头：默认只给观看者发包，其他玩家看不到假玩家/皮肤/动作。
     * <p>
     * 这是原版实体追踪的逐玩家开关（{@code ChunkMap.TrackedEntity} 用它决定
     * 是否与某个玩家建立配对），因此隐藏发生在服务端、无需额外发包；
     * {@code commands.yml} 的 {@code view.hideFromOthers=false} 可退回旧的“附近玩家可见”。
     */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (!CommandConfig.getInstance().viewHideFromOthers()) {
            return super.broadcastToPlayer(player);
        }
        return audience.allows(player.getUUID());
    }

    /** 允许该观看者看到这个人偶（会话创建 / 共享借用时登记）。 */
    void allowViewer(UUID viewer) {
        audience.allow(viewer);
    }

    /** 取消观看者可见性（停止观看 / 不再借用）。 */
    void denyViewer(UUID viewer) {
        audience.deny(viewer);
    }

    /** 当前有多少观看者能看到这个人偶（诊断/测试用）。 */
    int viewerCount() {
        return audience.size();
    }

    public UUID sourceUuid() {
        return sourceUuid;
    }

    public void applyProfile(GameProfile profile) {
        if (profile == null) {
            return;
        }
        // 离线/UUID 回退名可能不是合法玩家名，ResolvableProfile 编码会拒绝；此时只保留自定义名牌
        String name = profile.name();
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
            return;
        }
        this.entityData.set(DATA_PROFILE, ResolvableProfile.createResolved(profile));
    }

    /**
     * 回看形态：保留正常渲染包围盒（名牌/皮肤/客户端剔除需要）。
     * {@code noPhysics=true} + 本方法返回 false = 实质零碰撞。
     */
    @Override
    public boolean canBeCollidedWith(net.minecraft.world.entity.Entity entity) {
        return false;
    }

    /**
     * 只给旁观相机选中用：原版在 spectator 下左键实体走 {@code spectate(entity)}，
     * 不会攻击。普通玩家能瞄到也打不动（{@link #isAttackable()} 为 false）。
     */
    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** 不挡投射物：回看人偶不是世界实体。 */
    @Override
    public boolean canBeHitByProjectile() {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE_ENTITY;
    }

    /** 恒不受伤、不播反馈：回看没有可交互路径。 */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    /**
     * 右键：只给“正在回看本人的观看者”打开只读背包；其他玩家仍然 PASS，
     * 不捕捉、不拴绳、不产生持久后果。
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, net.minecraft.world.phys.Vec3 location) {
        if (player instanceof net.minecraft.server.level.ServerPlayer sp
                && ViewManager.getInstance().isViewing(sp.getUUID())) {
            ViewManager.getInstance().inspectByRightClick(sp, this);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    /** 回看人偶永不掉落录制装备；停止/替换走 discard，也不经过死亡掉落。 */
    @Override
    protected void dropEquipment(ServerLevel level) {
    }

    @Override
    public void setCustomNameVisible(boolean visible) {
        super.setCustomNameVisible(nameTag && visible);
    }
}
