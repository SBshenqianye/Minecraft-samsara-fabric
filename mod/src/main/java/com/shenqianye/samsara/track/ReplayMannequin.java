package com.shenqianye.samsara.track;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 回放人偶：原版 {@link Mannequin}（26.3）的子类。
 * <p>
 * 用子类而不是另注册实体类型，是为了让<b>客户端免装模组</b>：{@link Mannequin#Mannequin(Level)}
 * 会把实体类型固定为原版 {@code minecraft:mannequin}，客户端用原生 {@code ClientMannequin}
 * 渲染（含玩家皮肤）。
 * <p>
 * <b>回放期间</b>：无重力、每 tick 由 {@link TrackReplay} 直接 snapTo 轨迹位置，
 * 无 AI、不自己走；可以被玩家攻击/交互（第一次即触发<b>捕捉</b>）。
 * <p>
 * <b>捕捉后</b>：变成普通实体——恢复重力与碰撞、可被推/活塞搬运/拴绳牵引、可被攻击杀死。
 * 注意 Mannequin 继承 {@code Avatar → LivingEntity}，<b>不是 Mob</b>，因此没有
 * {@code Mob#setPersistenceRequired}；但非 Mob 实体本来就不参与自然消失（despawn），
 * 并会随区块写入 entities 存档，语义等价于「不因距离自然消失」。
 * <p>
 * 捕捉只影响该实体与该玩家的轨迹回放：不保护所在区块、不写 trophies.json、
 * 不做区块删除后重建，方块逆熵完全照旧。
 */
public class ReplayMannequin extends Mannequin implements Leashable {
    /** 捕捉回调：由 TrackReplay 注入（停止回放、记录 cutoff、广播） */
    public interface CaptureHandler {
        void onCaptured(ReplayMannequin mannequin, ServerPlayer player);
    }

    private final CaptureHandler captureHandler;
    /** 回放源头玩家（与原版实体类型无关，仅用于广播/调试） */
    private final UUID sourceUuid;
    /** 回放所属周目（capture cutoff 用） */
    private final int cycleId;
    private boolean captured;
    private Leashable.LeashData leashData;

    public ReplayMannequin(Level level, UUID sourceUuid, int cycleId, CaptureHandler captureHandler) {
        super(level);
        this.sourceUuid = sourceUuid;
        this.cycleId = cycleId;
        this.captureHandler = captureHandler;
        setNoGravity(true);      // 回放位置完全由 TrackReplay 决定
        setSilent(true);
        setCustomNameVisible(true);
    }

    public UUID sourceUuid() {
        return sourceUuid;
    }

    public int cycleId() {
        return cycleId;
    }

    public boolean isCaptured() {
        return captured;
    }

    /**
     * 设置玩家皮肤/名称：{@code ResolvableProfile.createResolved(GameProfile)} +
     * 受保护的 {@code DATA_PROFILE}。客户端按原版人偶渲染，无需额外模组。
     */
    public void applyProfile(GameProfile profile) {
        if (profile == null) {
            return;
        }
        this.entityData.set(DATA_PROFILE, ResolvableProfile.createResolved(profile));
        if (profile.name() != null && !profile.name().isBlank()) {
            setCustomName(Component.literal(profile.name()));
        }
    }

    /** 捕捉：停在原地、清空速度、恢复重力/碰撞，之后就是普通实体 */
    public void markCaptured() {
        captured = true;
        setNoGravity(false);
        setDeltaMovement(Vec3.ZERO);
        setCustomNameVisible(true);
    }

    /**
     * 受击：回放期间第一次被 {@link ServerPlayer} 攻击即捕捉，并取消本次伤害；
     * 其他伤害（摔落/岩浆/爆炸等）与捕捉后的伤害照常交给原版处理（可以被杀死）。
     */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (!captured && source.getEntity() instanceof ServerPlayer player) {
            if (captureHandler != null) {
                captureHandler.onCaptured(this, player);
            }
            return false;
        }
        return super.hurtServer(level, source, amount);
    }

    /**
     * 交互：回放期间第一次被玩家右键即捕捉；
     * 捕捉后允许用拴绳牵引（搬运人偶的正常方式之一，另有推/活塞）。
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (!captured && player instanceof ServerPlayer sp) {
            if (captureHandler != null) {
                captureHandler.onCaptured(this, sp);
            }
            return InteractionResult.SUCCESS;
        }
        if (captured && player instanceof ServerPlayer
                && player.getItemInHand(hand).is(Items.LEAD)) {
            if (!isLeashed()) {
                setLeashedTo(player, true);
            } else {
                dropLeash();
            }
            return InteractionResult.SUCCESS;
        }
        return super.interact(player, hand, location);
    }

    /** 回放期间不被推动（避免轨迹被外力干扰）；捕捉后恢复为可推的普通实体 */
    @Override
    public boolean isPushable() {
        return captured;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        // 回放期间活塞完全忽略人偶（既不推它，也不被它挡住）；捕捉后恢复正常搬运
        return captured ? PushReaction.PUSH_PULL : PushReaction.IGNORE_ENTITY;
    }

    // ---- Leashable（只有捕捉后的普通实体才需要；回放期间没人会拴它）----

    @Override
    public Leashable.LeashData getLeashData() {
        return leashData;
    }

    @Override
    public void setLeashData(Leashable.LeashData data) {
        this.leashData = data;
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel serverLevel) {
            Leashable.tickLeash(serverLevel, this);
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putBoolean("samsara_captured", captured);
        writeLeashData(out, leashData);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        captured = in.getBooleanOr("samsara_captured", false);
        readLeashData(in);
        if (captured) {
            // 重启后仍是普通实体：重力/碰撞照常
            setNoGravity(false);
            setCustomNameVisible(true);
        }
    }
}
