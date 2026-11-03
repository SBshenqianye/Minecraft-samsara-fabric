package com.shenqianye.samsara.track;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.ZombieHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 捕捉式回放里的“真实僵尸马”。
 * <p>
 * 回放期间：无重力、无 AI、静音、不可推动，由 {@link TrackReplay} 每 tick 定位；
 * 玩家攻击/右键它（或攻击骑在它身上的人偶）都会走同一条捕捉流程。
 * 捕捉后：变成可保留的普通持久实体——保留古名、最大生命/移速/跳跃、鞍与幼崽状态，
 * 保持 {@code NoAI}（不自己乱走）但玩家仍可骑乘；停止回放不会移除它。
 * <p>
 * 选僵尸马而不是骷髅马，是因为僵尸马完全不能自然生成（骷髅马可由骷髅陷阱出现）。
 */
public class ReplayHorse extends ZombieHorse {
    public interface CaptureHandler {
        void onCaptured(ReplayHorse horse, ServerPlayer player);
    }

    private final UUID sourceUuid;
    private final int cycleId;
    private final CaptureHandler captureHandler;
    private String ancientName = "";
    private boolean captured;

    public ReplayHorse(Level level, UUID sourceUuid, int cycleId, CaptureHandler captureHandler) {
        super(EntityTypes.ZOMBIE_HORSE, level);
        this.sourceUuid = sourceUuid;
        this.cycleId = cycleId;
        this.captureHandler = captureHandler;
        setNoGravity(true);
        setNoAi(true);
        setSilent(true);
        setCustomNameVisible(true);
        setPermanentlyInvulnerable(true);
    }

    public UUID sourceUuid() {
        return sourceUuid;
    }

    public int cycleId() {
        return cycleId;
    }

    public String ancientName() {
        return ancientName;
    }

    public boolean isCaptured() {
        return captured;
    }

    /** 回放开始时按录制马的属性配置；类型固定为僵尸马。 */
    public void configure(TrackEvents.VehicleInfo info, String name) {
        this.ancientName = name == null ? "" : name;
        setCustomName(Component.literal(ancientName));
        setCustomNameVisible(true);
        setTamed(true);
        setNoAi(true);
        setSilent(true);
        setNoGravity(true);
        setPermanentlyInvulnerable(true);
        if (info != null) {
            if (info.maxHealth() > 0) {
                getAttribute(Attributes.MAX_HEALTH).setBaseValue(info.maxHealth());
                setHealth(info.maxHealth());
            }
            if (info.movementSpeed() > 0) {
                getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(info.movementSpeed());
            }
            if (info.jumpStrength() > 0) {
                getAttribute(Attributes.JUMP_STRENGTH).setBaseValue(info.jumpStrength());
            }
            if (info.baby()) {
                setBaby(true);
            }
        }
        // 录制时玩家正在骑这匹马，说明它可骑；僵尸马默认没有鞍，补上以保证捕捉后可操控。
        setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
    }

    /** 捕捉：停止回放时保留为普通持久实体。 */
    public void markCaptured() {
        captured = true;
        com.shenqianye.samsara.Samsara.LOGGER.info(
                "[回放] 回放马捕捉标记: {} captured={} shouldSave={}",
                ancientName, captured, shouldBeSaved());
        setPermanentlyInvulnerable(false);
        setNoGravity(false);
        setNoAi(true);
        setPersistenceRequired();
        setTamed(true);
        setSilent(true);
        setCustomNameVisible(true);
        setHealth(getMaxHealth());
        setDeltaMovement(Vec3.ZERO);
        if (getItemBySlot(EquipmentSlot.SADDLE).isEmpty()) {
            setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
        }
    }

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

    @Override
    public InteractionResult mobInteract(net.minecraft.world.entity.player.Player player,
                                         InteractionHand hand) {
        if (!captured && player instanceof ServerPlayer sp) {
            if (captureHandler != null) {
                captureHandler.onCaptured(this, sp);
            }
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public boolean isPushable() {
        // 与原版马一致：没被骑时可推动，被骑时不可推动；回放期间始终不可推动
        return captured && !isVehicle();
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return captured && !isVehicle() ? PushReaction.PUSH_PULL : PushReaction.IGNORE_ENTITY;
    }

    @Override
    public boolean removeWhenFarAway(double distanceSquared) {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return captured;
    }

    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        com.shenqianye.samsara.Samsara.LOGGER.info(
                "[回放] 回放马被移除: {} reason={} captured={}",
                ancientName, reason, captured);
        super.remove(reason);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putBoolean("samsara_captured", captured);
        out.putString("samsara_ancient_name", ancientName);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        captured = in.getBooleanOr("samsara_captured", false);
        ancientName = in.getStringOr("samsara_ancient_name", "");
        if (captured) {
            setNoGravity(false);
            setNoAi(true);
            setPersistenceRequired();
            setCustomNameVisible(true);
        }
    }
}

