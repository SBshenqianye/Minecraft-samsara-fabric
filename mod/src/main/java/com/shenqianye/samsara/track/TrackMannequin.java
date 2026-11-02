package com.shenqianye.samsara.track;

import com.shenqianye.samsara.Samsara;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.util.Prediction;

import java.util.HashSet;
import java.util.Set;

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
    /** 真实载具类型提示去重。 */
    private static final Set<String> LOGGED_VEHICLES = new HashSet<>();
    private static final Set<Integer> LOGGED_FLIGHT_MODES = new HashSet<>();

    /** -1 = 未知（旧轨迹，沿用 flags）；0 none；1 elytra；2 creative。 */
    private int flightMode = -1;
    /** 真实载具实体类型 id；空串 = 无载具。 */
    private String vehicleType = "";
    /** 记录的选中快捷栏 0~8（只用于状态/检查，客户端人偶没有快捷栏）。 */
    private int selectedSlot;
    /** 捕捉后的普通实体死亡时是否掉落录制装备（F-107）。 */
    private boolean equipmentDropAllowed = true;

    protected TrackMannequin(Level level) {
        super(level);
    }

    /** 统一入口：应用 6 个可见槽 + 选中槽（F-105）。 */
    public final void applyEquipment(ItemStack[] slots, int selectedSlot) {
        this.selectedSlot = Math.max(0, Math.min(8, selectedSlot));
        setItemSlot(EquipmentSlot.MAINHAND, stack(slots, TrackEvents.SLOT_MAINHAND));
        setItemSlot(EquipmentSlot.OFFHAND, stack(slots, TrackEvents.SLOT_OFFHAND));
        setItemSlot(EquipmentSlot.HEAD, stack(slots, TrackEvents.SLOT_HEAD));
        setItemSlot(EquipmentSlot.CHEST, stack(slots, TrackEvents.SLOT_CHEST));
        setItemSlot(EquipmentSlot.LEGS, stack(slots, TrackEvents.SLOT_LEGS));
        setItemSlot(EquipmentSlot.FEET, stack(slots, TrackEvents.SLOT_FEET));
    }

    private static ItemStack stack(ItemStack[] slots, int index) {
        if (slots == null || index >= slots.length || slots[index] == null) {
            return ItemStack.EMPTY;
        }
        return slots[index].copy();
    }

    public final int selectedSlot() {
        return selectedSlot;
    }

    /** 统一入口：区分 elytra / creative / none，避免把创造飞行画成鞘翅。 */
    public final void applyFlightMode(int mode) {
        int normalized = mode == TrackEvents.FLIGHT_ELYTRA || mode == TrackEvents.FLIGHT_CREATIVE
                ? mode : TrackEvents.FLIGHT_NONE;
        if (normalized != flightMode) {
            flightMode = normalized;
            if (LOGGED_FLIGHT_MODES.add(normalized)) {
                Samsara.LOGGER.info("[回放] 飞行类型: {}", TrackEventCursor.flightName(normalized));
            }
        }
    }

    public final int flightMode() {
        return flightMode;
    }

    /** 统一入口：记录真实载具实体类型；完整渲染可后置，但状态可区分。 */
    public final void applyVehicleType(String entityTypeId) {
        String value = entityTypeId == null ? "" : entityTypeId;
        if (!value.equals(vehicleType)) {
            vehicleType = value;
            if (!value.isEmpty() && LOGGED_VEHICLES.add(value)) {
                Samsara.LOGGER.info("[回放] 载具类型: {}（本轮以骑乘姿态近似，真实载具渲染待后续）", value);
            }
        }
    }

    public final String vehicleType() {
        return vehicleType;
    }

    /** 前向跨越 SWING：还原挥动动作。 */
    public final void applySwing(int handId, int animationId) {
        InteractionHand hand = handId == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        SwingAnimationType[] types = SwingAnimationType.values();
        SwingAnimationType type = animationId >= 0 && animationId < types.length
                ? types[animationId] : SwingAnimationType.WHACK;
        swing(hand, new SwingAnimation(type, 6), true);
    }

    public final void applyUseStart(int handId) {
        startUsingItem(handId == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
    }

    public final void applyUseStop() {
        stopUsingItem();
    }

    /** 跳转/倒放：清空挥动与使用状态，后续按快照重建。 */
    public final void resetActionState() {
        if (isUsingItem()) {
            stopUsingItem();
        }
    }

    public final void setEquipmentDropAllowed(boolean allowed) {
        this.equipmentDropAllowed = allowed;
    }

    public final boolean equipmentDropAllowed() {
        return equipmentDropAllowed;
    }

    /** 只有捕捉后的普通实体允许走原版死亡掉落；子类覆写。 */
    protected boolean equipmentDroppable() {
        return false;
    }

    /**
     * 死亡掉落：默认允许“捕捉后的人偶”掉落录制装备（用户接受复制）；
     * 关闭后任何路径都不掉落录制物品。回看/停止/discard 不走死亡掉落。
     */
    @Override
    protected void dropEquipment(ServerLevel level) {
        // 原版 Mannequin/Avatar 没有 Mob 的装备掉落实现（LivingEntity.dropEquipment 为空），
        // 这里按用户裁决显式掉落 6 个录制可见槽；关闭时一个都不掉。
        if (!equipmentDroppable() || !equipmentDropAllowed) {
            return;
        }
        EquipmentSlot[] slots = {
                EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET
        };
        for (EquipmentSlot slot : slots) {
            ItemStack stack = getItemBySlot(slot);
            if (!stack.isEmpty()) {
                drop(stack.copy(), false, Prediction.SERVER_ONLY);
                setItemSlot(slot, ItemStack.EMPTY);
            }
        }
    }

    /**
     * 按当前轨迹点的 flags 应用可见姿态。回放/回看管线每 tick 调用一次；
     * 同一个 flags 重复调用是幂等的（受伤闪烁按 level game time 每 10 tick 续一次）。
     */
    public final void applyTrackFlags(int flags) {
        TrackPose.State state = TrackPose.of(flags, flightMode);
        setShiftKeyDown(state.sneak());
        setSprinting(state.sprint());
        setSwimming(state.swimming());
        // 26.3：Pose 只影响尺寸/视线高度，鞘翅姿态还要 shared flag 才会同步给客户端。
        // creative/none 已由 TrackPose 排除，不会再画成鞘翅。
        setSharedFlag(FLAG_FALL_FLYING, state.flying());
        setPose(poseOf(state.posture()));
        if (state.posture() == TrackPose.Posture.RIDING && !ridingApproximationLogged) {
            ridingApproximationLogged = true;
            Samsara.LOGGER.info("[回放] 骑乘姿态暂用蹲姿近似；真实载具类型已记录，实体渲染待后续");
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
