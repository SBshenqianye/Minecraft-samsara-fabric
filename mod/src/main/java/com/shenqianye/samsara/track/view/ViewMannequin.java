package com.shenqianye.samsara.track.view;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
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
 */
public class ViewMannequin extends Mannequin {
    private final UUID sourceUuid;
    private final boolean nameTag;

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

    /** 回放姿态：潜行/游泳/疾跑来自轨迹 flags。 */
    public void applyTrackFlags(int flags) {
        boolean sneak = (flags & com.shenqianye.samsara.track.TrackStore.FLAG_SNEAK) != 0;
        boolean sprint = (flags & com.shenqianye.samsara.track.TrackStore.FLAG_SPRINT) != 0;
        boolean swimming = (flags & com.shenqianye.samsara.track.TrackStore.FLAG_SWIMMING) != 0;
        setShiftKeyDown(sneak);
        setSprinting(sprint);
        setSwimming(swimming);
        setPose(swimming ? Pose.SWIMMING : sneak ? Pose.CROUCHING : Pose.STANDING);
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

    /** 右键不接管：不捕捉、不拴绳、不产生持久后果。 */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, net.minecraft.world.phys.Vec3 location) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public void setCustomNameVisible(boolean visible) {
        super.setCustomNameVisible(nameTag && visible);
    }
}
