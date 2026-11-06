package com.shenqianye.samsara.insurance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.lottery.ItemValueService;
import com.shenqianye.samsara.lottery.LotteryItemCodec;
import com.shenqianye.samsara.lottery.LotteryService;
import com.shenqianye.samsara.storage.StoragePaths;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 物品保险：投保时保存完整 ItemStack 快照；理赔前扫描本人/在线玩家/周围容器与掉落物，
 * 确认原物已不存在才还原。保费 = 价值 × 倍数（默认 2×），从副手物品扣除。
 */
public final class InsuranceService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static InsuranceService instance;

    private MinecraftServer server;
    private RegistryAccess registries;
    private Path root;
    private final List<PolicyData> policies = new ArrayList<>();
    private final List<ClaimData> claims = new ArrayList<>();

    private InsuranceService() {
    }

    public static synchronized InsuranceService getInstance() {
        if (instance == null) instance = new InsuranceService();
        return instance;
    }

    public synchronized void init(MinecraftServer server) {
        this.server = server;
        this.registries = server.registryAccess();
        this.root = StoragePaths.get().worldDataRoot().resolve("insurance");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[insurance] 无法创建目录：{}", e.getMessage());
        }
        load();
    }

    public synchronized void onServerStopping() {
        save();
    }

    public synchronized void onJoin(ServerPlayer player) {
        for (PolicyData p : policies) {
            if ("REVIVE_PENDING".equals(p.status)
                    && p.ownerUuid.equals(player.getUUID().toString())) {
                revive(p, player);
            }
        }
        int active = 0;
        for (PolicyData p : policies) {
            if (p.ownerUuid.equals(player.getUUID().toString())
                    && "ACTIVE".equals(p.status) && p.expiresAt > System.currentTimeMillis()) {
                active++;
            }
        }
        if (active > 0) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[死人乐透·保险] ▸ 你有 " + active + " 份有效保单，输入 /samsara lottery insurance list 查看。"));
        }
    }

    public synchronized void tick(MinecraftServer server) {
        this.server = server;
        long now = System.currentTimeMillis();
        for (PolicyData p : policies) {
            if ("REVIVE_PENDING".equals(p.status) && p.reviveAt <= now) {
                ServerPlayer owner = player(p.ownerUuid);
                if (owner != null) {
                    revive(p, owner);
                }
            }
        }
    }

    public synchronized void onDeath(LivingEntity entity) {
        String uuid = entity.getUUID().toString();
        boolean changed = false;
        for (PolicyData p : policies) {
            if ("ENTITY".equals(p.type) && uuid.equals(p.entityUuid) && "ACTIVE".equals(p.status)) {
                p.status = "REVIVE_PENDING";
                p.reviveAt = System.currentTimeMillis() + 3000L;
                p.lastDeathAt = System.currentTimeMillis();
                changed = true;
            }
        }
        if (changed) save();
    }

    public synchronized void onDeath(ServerPlayer player) {
        long now = System.currentTimeMillis();
        for (PolicyData p : policies) {
            if (p.ownerUuid.equals(player.getUUID().toString()) && "ACTIVE".equals(p.status)) {
                p.lastDeathAt = now;
            }
        }
        save();
    }

    public synchronized Result insureItem(ServerPlayer player) {
        if (!InsuranceConfig.getInstance().enabled()) {
            return Result.error("保险未启用。");
        }
        ItemStack hand = player.getInventory().getItem(player.getInventory().getSelectedSlot());
        if (hand.isEmpty()) {
            return Result.error("请把要投保的物品拿在主手。");
        }
        if (!InsuranceConfig.getInstance().allowStackable() && hand.getMaxStackSize() > 1) {
            return Result.error("默认只保单个不可堆叠物品（高级附魔/工具/护甲/鞘翅）。");
        }
        String item = LotteryItemCodec.encode(hand, registries);
        if (item.isBlank()) {
            return Result.error("物品快照失败。");
        }
        ItemValueService values = ItemValueService.getInstance();
        double value = values.unitValue(hand);
        double premium = value * InsuranceConfig.getInstance().premiumMultiplier();
        ItemStack off = player.getOffhandItem();
        double unit = values.unitValue(off);
        double available = unit * off.getCount();
        if (unit <= 0.0 || available + 0.0001 < premium) {
            return Result.error("副手保费不足：需要 " + values.format(premium)
                    + " VP（价值 " + values.format(value) + " × "
                    + InsuranceConfig.getInstance().premiumMultiplier() + "），当前 "
                    + values.format(Math.max(0, available)) + " VP。");
        }
        int take = (int) Math.ceil(premium / unit);
        take = Math.max(1, Math.min(take, off.getCount()));
        if (unit * take + 0.0001 < premium) {
            return Result.error("副手物品不足以支付保费。");
        }
        off.shrink(take);
        player.setItemSlot(EquipmentSlot.OFFHAND, off.isEmpty() ? ItemStack.EMPTY : off);

        PolicyData policy = new PolicyData();
        policy.id = "ins-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        policy.ownerUuid = player.getUUID().toString();
        policy.ownerName = player.getGameProfile().name();
        policy.item = item;
        policy.value = value;
        policy.premium = unit * take;
        policy.insuredAt = System.currentTimeMillis();
        policy.expiresAt = policy.insuredAt + InsuranceConfig.getInstance().policyDays() * 86_400_000L;
        policy.status = "ACTIVE";
        policies.add(policy);
        save();
        return Result.ok(policy, "已投保：" + policy.id + " · 价值 "
                + values.format(value) + " VP · 保费 " + values.format(policy.premium) + " VP");
    }

    public synchronized List<PolicyData> policies(ServerPlayer player) {
        List<PolicyData> out = new ArrayList<>();
        for (PolicyData p : policies) {
            if (p.ownerUuid.equals(player.getUUID().toString())) {
                out.add(p);
            }
        }
        return out;
    }

    public synchronized Result insureEntity(ServerPlayer player) {
        if (!InsuranceConfig.getInstance().enabled()) {
            return Result.error("保险未启用。");
        }
        if (!InsuranceConfig.getInstance().entitiesEnabled()) {
            return Result.error("实体/宠物保险未启用（insurance.yml 的 entities.enabled）。");
        }
        LivingEntity target = null;
        double best = 25.0;
        for (LivingEntity candidate : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(6.0), e -> e != player)) {
            boolean pet = candidate instanceof TamableAnimal tamable
                    && tamable.getOwnerReference() != null;
            if (!pet && !candidate.hasCustomName()) continue;
            double distance = candidate.distanceToSqr(player);
            if (distance < best) {
                best = distance;
                target = candidate;
            }
        }
        if (target == null) {
            return Result.error("请靠近已驯服或已命名的生物（6 格内）。");
        }
        CompoundTag tag;
        try {
            var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                    net.minecraft.util.ProblemReporter.DISCARDING, player.level().registryAccess());
            target.saveWithoutId(output);
            tag = output.buildResult();
        } catch (RuntimeException e) {
            return Result.error("生物快照失败：" + e.getMessage());
        }
        tag.remove("UUID");
        String snapshot = tag.toString();
        double value = entityValue(target);
        double premium = value * InsuranceConfig.getInstance().premiumMultiplier();
        ItemStack off = player.getOffhandItem();
        double unit = ItemValueService.getInstance().unitValue(off);
        double available = unit * off.getCount();
        if (unit <= 0.0 || available + 0.0001 < premium) {
            return Result.error("副手保费不足：需要 " + ItemValueService.getInstance().format(premium)
                    + " VP（实体价值 " + ItemValueService.getInstance().format(value) + " × "
                    + InsuranceConfig.getInstance().premiumMultiplier() + "）。");
        }
        int take = (int) Math.ceil(premium / unit);
        take = Math.max(1, Math.min(take, off.getCount()));
        off.shrink(take);
        player.setItemSlot(EquipmentSlot.OFFHAND, off.isEmpty() ? ItemStack.EMPTY : off);

        PolicyData policy = new PolicyData();
        policy.type = "ENTITY";
        policy.id = "ins-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        policy.ownerUuid = player.getUUID().toString();
        policy.ownerName = player.getGameProfile().name();
        policy.entityUuid = target.getUUID().toString();
        policy.entityName = target.getName().getString();
        policy.entityType = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        policy.snapshot = snapshot;
        policy.value = value;
        policy.premium = unit * take;
        policy.insuredAt = System.currentTimeMillis();
        policy.expiresAt = policy.insuredAt + InsuranceConfig.getInstance().policyDays() * 86_400_000L;
        policy.status = "ACTIVE";
        policies.add(policy);
        save();
        return Result.ok(policy, "已投保生物：" + policy.entityName + " · " + policy.id
                + " · 价值 " + ItemValueService.getInstance().format(value) + " VP · 保费 "
                + ItemValueService.getInstance().format(policy.premium) + " VP");
    }

    private double entityValue(LivingEntity entity) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        double base = switch (id) {
            case "minecraft:horse", "minecraft:camel", "minecraft:sniffer" -> 64.0;
            case "minecraft:donkey", "minecraft:mule", "minecraft:llama", "minecraft:trader_llama" -> 48.0;
            case "minecraft:wolf", "minecraft:allay" -> 32.0;
            case "minecraft:cat", "minecraft:parrot", "minecraft:fox", "minecraft:axolotl" -> 16.0;
            default -> 32.0;
        };
        if (entity.hasCustomName()) base += 32.0;
        for (ItemStack stack : new ItemStack[]{
                entity.getItemBySlot(EquipmentSlot.HEAD),
                entity.getItemBySlot(EquipmentSlot.CHEST),
                entity.getItemBySlot(EquipmentSlot.LEGS),
                entity.getItemBySlot(EquipmentSlot.FEET),
                entity.getItemBySlot(EquipmentSlot.MAINHAND),
                entity.getItemBySlot(EquipmentSlot.OFFHAND)}) {
            base += ItemValueService.getInstance().valueOf(stack);
        }
        return base;
    }

    private void revive(PolicyData policy, ServerPlayer owner) {
        if (!(owner.level() instanceof ServerLevel level)) return;
        try {
            CompoundTag tag = TagParser.parseCompoundFully(policy.snapshot);
            tag.remove("CustomName");
            var input = net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag);
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(
                    net.minecraft.resources.Identifier.parse(policy.entityType));
            Entity entity = type == null ? null : type.create(level, EntitySpawnReason.COMMAND);
            if (entity != null) {
                entity.load(input);
                entity.snapTo(owner.getX(), owner.getY(), owner.getZ(), owner.getYRot(), 0.0f);
                if (entity instanceof LivingEntity living && policy.entityName != null
                        && !policy.entityName.isBlank()) {
                    living.setCustomName(net.minecraft.network.chat.Component.literal(policy.entityName));
                    living.setCustomNameVisible(true);
                }
                level.addFreshEntity(entity);
                policy.status = "REVIVED";
                policy.claimedAt = System.currentTimeMillis();
                addClaim(policy, policy.claimedAt);
                save();
                owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "[死人乐透·保险] ▸ 你的生物已复活：" + policy.entityName));
            } else {
                policy.status = "REVIVE_FAILED";
                save();
                owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "[死人乐透·保险] ▸ 生物复活失败：快照无法加载。"));
            }
        } catch (RuntimeException | com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            policy.status = "REVIVE_FAILED";
            save();
            owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[死人乐透·保险] ▸ 生物复活失败：" + e.getMessage()));
        }
    }

    /** 自动化自测：召唤命名狼 → 快照 → 杀死 → 恢复，返回是否成功。 */
    public synchronized Result selfTest(ServerPlayer owner) {
        if (!(owner.level() instanceof ServerLevel level)) {
            return Result.error("需要服务器世界。");
        }
        EntityType<?> wolfType = BuiltInRegistries.ENTITY_TYPE.getValue(
                net.minecraft.resources.Identifier.parse("minecraft:wolf"));
        Entity wolf = wolfType == null ? null : wolfType.create(level, EntitySpawnReason.COMMAND);
        if (!(wolf instanceof LivingEntity living)) {
            return Result.error("无法生成测试狼。");
        }
        living.setCustomName(net.minecraft.network.chat.Component.literal("InsuranceSelfTest"));
        living.setCustomNameVisible(true);
        living.snapTo(owner.getX() + 1, owner.getY(), owner.getZ(), 0.0f, 0.0f);
        level.addFreshEntity(living);
        CompoundTag tag;
        try {
            var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                    net.minecraft.util.ProblemReporter.DISCARDING, owner.level().registryAccess());
            living.saveWithoutId(output);
            tag = output.buildResult();
        } catch (RuntimeException e) {
            return Result.error("快照失败：" + e.getMessage());
        }
        tag.remove("UUID");
        PolicyData policy = new PolicyData();
        policy.type = "ENTITY";
        policy.id = "selftest-" + System.currentTimeMillis();
        policy.ownerUuid = owner.getUUID().toString();
        policy.ownerName = owner.getGameProfile().name();
        policy.entityUuid = living.getUUID().toString();
        policy.entityName = "InsuranceSelfTest";
        policy.snapshot = tag.toString();
        policy.value = 64.0;
        policy.expiresAt = System.currentTimeMillis() + 86_400_000L;
        policy.status = "ACTIVE";
        policies.add(policy);
        living.kill(level);
        onDeath(living);
        policy.reviveAt = 0L;
        revive(policy, owner);
        boolean ok = "REVIVED".equals(policy.status);
        if (!ok) policies.remove(policy);
        save();
        return ok ? Result.ok(policy, "实体复活自测通过：" + policy.id)
                : Result.error("实体复活自测失败：状态 " + policy.status);
    }

    public synchronized Result claim(ServerPlayer player, String id) {
        PolicyData policy = find(id);
        if (policy == null || !policy.ownerUuid.equals(player.getUUID().toString())) {
            return Result.error("找不到你的保单：" + id);
        }
        if (!"ACTIVE".equals(policy.status)) {
            return Result.error("保单状态为 " + policy.status + "，不能理赔。");
        }
        long now = System.currentTimeMillis();
        if (policy.expiresAt < now) {
            policy.status = "EXPIRED";
            save();
            return Result.error("保单已过期。");
        }
        if (!canClaimNow(player.getUUID().toString(), now)) {
            return Result.error("理赔冷却中，或今日理赔次数已满。");
        }
        ItemStack original = LotteryItemCodec.decode(policy.item, registries);
        if (original.isEmpty()) {
            return Result.error("保单快照损坏，请联系管理员。");
        }
        String encoded = LotteryItemCodec.encode(original, registries);
        if (LotteryService.getInstance().holdsEncodedItem(encoded)) {
            return Result.error("该物品仍在乐透下注池/滚存/基金/待领取中，不能按丢失理赔。");
        }
        if (existsAnywhere(player, original)) {
            return Result.error("原物仍然存在（背包/末影箱/在线玩家/周围容器或掉落物），不能理赔。");
        }
        if (policy.value > InsuranceConfig.getInstance().largeClaimValue()) {
            policy.status = "PENDING";
            save();
            return Result.pending(policy, "大额理赔已提交审核：" + policy.id);
        }
        if (!give(player, original)) {
            return Result.error("背包空间不足，物品未发放；腾出空间后再试。");
        }
        policy.status = "CLAIMED";
        policy.claimedAt = now;
        addClaim(policy, now);
        save();
        return Result.ok(policy, "理赔成功：" + original.getHoverName().getString());
    }

    public synchronized Result approve(String id) {
        PolicyData policy = find(id);
        if (policy == null) return Result.error("找不到保单：" + id);
        if (!"PENDING".equals(policy.status)) return Result.error("保单不在待审核状态。");
        ServerPlayer owner = player(policy.ownerUuid);
        ItemStack original = LotteryItemCodec.decode(policy.item, registries);
        if (original.isEmpty()) return Result.error("保单快照损坏。");
        if (owner != null) {
            if (!give(owner, original)) {
                return Result.error("背包空间不足，物品未发放；腾出空间后再试。");
            }
        } else {
            ClaimData claim = new ClaimData();
            claim.ownerUuid = policy.ownerUuid;
            claim.ownerName = policy.ownerName;
            claim.item = policy.item;
            claim.value = policy.value;
            claim.at = System.currentTimeMillis();
            claims.add(claim);
        }
        policy.status = "CLAIMED";
        policy.claimedAt = System.currentTimeMillis();
        addClaim(policy, policy.claimedAt);
        save();
        return Result.ok(policy, "已审核通过：" + policy.id);
    }

    /**
     * 乐透下注消耗物品时调用：命中的有效保单立即作废，之后不能再按“意外丢失”理赔。
     */
    public synchronized boolean onItemUsedAsBet(String encoded, String ownerUuid) {
        if (encoded == null || encoded.isBlank() || ownerUuid == null) {
            return false;
        }
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (PolicyData p : policies) {
            if (!p.ownerUuid.equals(ownerUuid)
                    || (!"ACTIVE".equals(p.status) && !"PENDING".equals(p.status))) {
                continue;
            }
            if (!encoded.equals(p.item)) {
                continue;
            }
            p.status = "VOID";
            p.voidReason = "已用于乐透下注";
            p.voidedAt = now;
            changed = true;
        }
        if (changed) {
            save();
        }
        return changed;
    }

    /** 公开聚合：只给保单总数与状态计数，不暴露主人、ID、价值。 */
    public synchronized Map<String, Object> summary() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("enabled", InsuranceConfig.getInstance().enabled());
        out.put("total", policies.size());
        int active = 0;
        int pending = 0;
        int claimed = 0;
        for (PolicyData p : policies) {
            switch (p.status) {
                case "ACTIVE", "REVIVE_PENDING" -> active++;
                case "PENDING" -> pending++;
                case "CLAIMED", "REVIVED" -> claimed++;
                default -> { }
            }
        }
        out.put("active", active);
        out.put("pending", pending);
        out.put("claimed", claimed);
        return out;
    }

    public synchronized Map<String, Object> model() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("enabled", InsuranceConfig.getInstance().enabled());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PolicyData p : policies) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", p.id);
            row.put("owner", p.ownerName);
            row.put("value", p.value);
            row.put("premium", p.premium);
            row.put("status", p.status);
            row.put("expiresAt", p.expiresAt);
            row.put("voidReason", p.voidReason);
            row.put("voidedAt", p.voidedAt);
            rows.add(row);
        }
        out.put("policies", rows);
        return out;
    }

    private boolean canClaimNow(String owner, long now) {
        InsuranceConfig c = InsuranceConfig.getInstance();
        long dayAgo = now - 86_400_000L;
        int today = 0;
        long last = 0;
        for (ClaimData claim : claims) {
            if (claim.ownerUuid.equals(owner)) {
                if (claim.at > dayAgo) today++;
                if (claim.at > last) last = claim.at;
            }
        }
        if (today >= c.maxClaimsPerDay()) return false;
        return c.claimCooldownMinutes() <= 0
                || last == 0
                || now - last >= c.claimCooldownMinutes() * 60_000L;
    }

    private boolean existsAnywhere(ServerPlayer player, ItemStack original) {
        String encoded = LotteryItemCodec.encode(original, registries);
        if (encoded.isBlank()) return true;
        if (contains(player.getInventory(), encoded)) return true;
        if (contains(player.getEnderChestInventory(), encoded)) return true;
        if (server == null) return false;
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (contains(other.getInventory(), encoded)) return true;
            if (contains(other.getEnderChestInventory(), encoded)) return true;
        }
        if (!(player.level() instanceof ServerLevel level)) return false;
        int radius = InsuranceConfig.getInstance().scanRadius();
        AABB box = new AABB(player.blockPosition()).inflate(radius);
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (encoded.equals(LotteryItemCodec.encode(entity.getItem(), registries))) return true;
        }
        int minChunkX = (player.blockPosition().getX() - radius) >> 4;
        int maxChunkX = (player.blockPosition().getX() + radius) >> 4;
        int minChunkZ = (player.blockPosition().getZ() - radius) >> 4;
        int maxChunkZ = (player.blockPosition().getZ() + radius) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    if (e.getValue() instanceof Container container && contains(container, encoded)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean contains(Container container, String encoded) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && encoded.equals(LotteryItemCodec.encode(stack, registries))) {
                return true;
            }
        }
        return false;
    }

    private PolicyData find(String id) {
        for (PolicyData p : policies) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    private ServerPlayer player(String uuid) {
        if (server == null) return null;
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(uuid));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean give(ServerPlayer player, ItemStack stack) {
        ItemStack copy = stack.copy();
        player.getInventory().add(copy);
        player.containerMenu.broadcastChanges();
        // 放不下的部分由调用方保留保单/待审核，不丢到地上。
        return copy.isEmpty();
    }

    private void addClaim(PolicyData policy, long at) {
        ClaimData claim = new ClaimData();
        claim.ownerUuid = policy.ownerUuid;
        claim.ownerName = policy.ownerName;
        claim.item = policy.item;
        claim.value = policy.value;
        claim.at = at;
        claims.add(claim);
    }

    private void load() {
        if (root == null) return;
        Path p = root.resolve("policies.json");
        if (Files.isRegularFile(p)) {
            try {
                PolicyList list = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), PolicyList.class);
                if (list != null && list.policies != null) {
                    policies.clear();
                    policies.addAll(list.policies);
                }
            } catch (RuntimeException | IOException e) {
                Samsara.LOGGER.warn("[insurance] 读取保单失败：{}", e.getMessage());
            }
        }
        Path c = root.resolve("claims.json");
        if (Files.isRegularFile(c)) {
            try {
                ClaimList list = GSON.fromJson(Files.readString(c, StandardCharsets.UTF_8), ClaimList.class);
                if (list != null && list.claims != null) {
                    claims.clear();
                    claims.addAll(list.claims);
                }
            } catch (RuntimeException | IOException e) {
                Samsara.LOGGER.warn("[insurance] 读取理赔记录失败：{}", e.getMessage());
            }
        }
    }

    private void save() {
        if (root == null) return;
        PolicyList list = new PolicyList();
        list.policies = new ArrayList<>(policies);
        write(root.resolve("policies.json"), GSON.toJson(list));
        ClaimList claimsList = new ClaimList();
        claimsList.claims = new ArrayList<>(claims);
        write(root.resolve("claims.json"), GSON.toJson(claimsList));
    }

    private void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[insurance] 写入 {} 失败：{}", file, e.getMessage());
        }
    }

    public static final class PolicyData {
        public String id = "";
        public String type = "ITEM";
        public String ownerUuid = "";
        public String ownerName = "";
        public String item = "";
        public String snapshot = "";
        public String entityUuid = "";
        public String entityName = "";
        public String entityType = "";
        public double value;
        public double premium;
        public long insuredAt;
        public long expiresAt;
        public long lastDeathAt;
        public long claimedAt;
        public long reviveAt;
        public long voidedAt;
        public String status = "ACTIVE";
        public String voidReason = "";
    }

    public static final class ClaimData {
        public String ownerUuid = "";
        public String ownerName = "";
        public String item = "";
        public double value;
        public long at;
    }

    public static final class PolicyList {
        public List<PolicyData> policies = new ArrayList<>();
    }

    public static final class ClaimList {
        public List<ClaimData> claims = new ArrayList<>();
    }

    public record Result(boolean ok, boolean pending, String message, PolicyData policy) {
        static Result ok(PolicyData p, String message) { return new Result(true, false, message, p); }
        static Result pending(PolicyData p, String message) { return new Result(true, true, message, p); }
        static Result error(String message) { return new Result(false, false, message, null); }
    }
}
