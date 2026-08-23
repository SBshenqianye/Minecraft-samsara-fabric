package com.shenqianye.samsara.claim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 圈地边界粒子配置。
 * <p>
 * 支持昼夜不同粒子类型，通过指令或 config/samsara/claim-particles.json 配置。
 */
public class ClaimParticleConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ClaimParticleConfig instance;

    /** 昼间边界粒子（默认：smoke，灰色可见） */
    private String dayParticle = "smoke";
    /** 夜间边界粒子（默认：end_rod，白色明亮） */
    private String nightParticle = "end_rod";
    /** 共享显示：true=TextDisplay 所有人可见，false=粒子仅持有者可见 */
    private boolean sharedDisplay = false;

    /** 粒子名称 → ParticleType 映射 */
    private static final Map<String, ParticleType<?>> PARTICLE_MAP;

    static {
        PARTICLE_MAP = new HashMap<>();
        PARTICLE_MAP.put("crit", ParticleTypes.CRIT);
        PARTICLE_MAP.put("enchant", ParticleTypes.ENCHANT);
        PARTICLE_MAP.put("smoke", ParticleTypes.SMOKE);
        PARTICLE_MAP.put("large_smoke", ParticleTypes.LARGE_SMOKE);
        PARTICLE_MAP.put("flame", ParticleTypes.FLAME);
        PARTICLE_MAP.put("soul_fire_flame", ParticleTypes.SOUL_FIRE_FLAME);
        PARTICLE_MAP.put("end_rod", ParticleTypes.END_ROD);
        PARTICLE_MAP.put("happy_villager", ParticleTypes.HAPPY_VILLAGER);
        PARTICLE_MAP.put("composter", ParticleTypes.COMPOSTER);
        PARTICLE_MAP.put("portal", ParticleTypes.PORTAL);
        PARTICLE_MAP.put("witch", ParticleTypes.WITCH);
        PARTICLE_MAP.put("cloud", ParticleTypes.CLOUD);
        PARTICLE_MAP.put("note", ParticleTypes.NOTE);
        PARTICLE_MAP.put("heart", ParticleTypes.HEART);
        PARTICLE_MAP.put("angry_villager", ParticleTypes.ANGRY_VILLAGER);
        PARTICLE_MAP.put("snowflake", ParticleTypes.SNOWFLAKE);
        PARTICLE_MAP.put("ash", ParticleTypes.ASH);
        PARTICLE_MAP.put("white_ash", ParticleTypes.WHITE_ASH);
    }

    private static final Path configPath = FabricLoader.getInstance().getConfigDir()
            .resolve("samsara").resolve("claim-particles.json");

    private ClaimParticleConfig() {
        load();
    }

    public static synchronized ClaimParticleConfig getInstance() {
        if (instance == null) {
            instance = new ClaimParticleConfig();
        }
        return instance;
    }

    /** 获取昼间粒子类型 */
    public ParticleType<?> getDayParticle() {
        return PARTICLE_MAP.getOrDefault(dayParticle.toLowerCase(), ParticleTypes.SMOKE);
    }

    /** 获取夜间粒子类型 */
    public ParticleType<?> getNightParticle() {
        return PARTICLE_MAP.getOrDefault(nightParticle.toLowerCase(), ParticleTypes.END_ROD);
    }

    /** 根据时间获取粒子类型（dayTime % 24000 < 12000 为白天） */
    public ParticleType<?> getParticleForTime(long dayTime) {
        boolean isDay = (dayTime % 24000) < 12000;
        return isDay ? getDayParticle() : getNightParticle();
    }

    /** 获取当前粒子配置名称 */
    public String getDayParticleName() { return dayParticle; }
    public String getNightParticleName() { return nightParticle; }
    public boolean isSharedDisplay() { return sharedDisplay; }

    /** 设置昼间粒子 */
    public void setDayParticle(String name) {
        this.dayParticle = name.toLowerCase();
        save();
    }

    /** 设置夜间粒子 */
    public void setNightParticle(String name) {
        this.nightParticle = name.toLowerCase();
        save();
    }

    /** 设置共享显示 */
    public void setSharedDisplay(boolean shared) {
        this.sharedDisplay = shared;
        save();
    }

    /** 获取可用粒子列表 */
    public static String getAvailableParticles() {
        return String.join(", ", PARTICLE_MAP.keySet());
    }

    private void save() {
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理] claim-particles.json 写入失败: {}", e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(configPath)) return;
        try {
            ClaimParticleConfig loaded = GSON.fromJson(
                    Files.readString(configPath, StandardCharsets.UTF_8), ClaimParticleConfig.class);
            if (loaded != null) {
                this.dayParticle = loaded.dayParticle;
                this.nightParticle = loaded.nightParticle;
            }
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理] claim-particles.json 解析失败: {}", e.getMessage());
        }
    }
}
