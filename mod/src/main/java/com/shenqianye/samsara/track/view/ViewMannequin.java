package com.shenqianye.samsara.track.view;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 回看幽灵人偶（设计 5.7.5 / F-82、F-83）。
 * <p>
 * 独立于捕捉式 {@code ReplayMannequin}：没有捕捉回调、没有 cutoffs 写入、不写存档。
 * {@code shouldBeSaved()=false} + 会话结束 {@code discard()}，异常/停服也统一清理。
 * <p>
 * 默认（未开 --interact）：无重力、noPhysics、零碰撞、不可拾取/攻击/推动。
 * {@code --interact}：可被攻击/推动，但 {@link #hurtServer} 恒返回 false，
 * 只播受击反馈，仍然不捕捉、不写盘。
 */
public class ViewMannequin extends Mannequin {
    private final UUID sourceUuid;
    private final boolean interact;
    private final boolean nameTag;

    public ViewMannequin(Level level, UUID sourceUuid, String name,
                         boolean interact, boolean glow, boolean nameTag) {
        super(level);
        this.sourceUuid = sourceUuid;
        this.interact = interact;
        this.nameTag = nameTag;
        setNoGravity(true);
        this.noPhysics = !interact;
        setSilent(!interact);
        setCustomName(Component.literal("[回看] " + name));
        setCustomNameVisible(nameTag);
        setGlowingTag(glow);
    }

    public UUID sourceUuid() {
        return sourceUuid;
    }

    public boolean interactive() {
        return interact;
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
     * 默认 {@code noPhysics=true} + 本方法返回 false = 实质零碰撞；
     * {@code --interact} 时恢复物理与碰撞，仅用于"可被推动"的观看体验。
     */
    @Override
    public boolean canBeCollidedWith(net.minecraft.world.entity.Entity entity) {
        return interact;
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

    @Override
    public boolean isPickable() {
        return interact;
    }

    @Override
    public boolean isAttackable() {
        return interact;
    }

    @Override
    public boolean isPushable() {
        return interact;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE_ENTITY;
    }

    /** 恒不受伤：--interact 也只播反馈，绝不触发捕捉/截断/写盘。 */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (interact) {
            this.hurtTime = 10;
            this.hurtDuration = 10;
            level.broadcastEntityEvent(this, (byte) 2);
            playSound(SoundEvents.PLAYER_HURT, 0.6f, 1.0f);
        }
        return false;
    }

    /** 右键不接管：不捕捉、不拴绳、不产生持久后果。 */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
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
