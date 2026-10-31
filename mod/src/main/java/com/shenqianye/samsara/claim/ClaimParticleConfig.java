package com.shenqianye.samsara.claim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shenqianye.samsara.Samsara;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 圈地边界粒子配置（持久化至 config/samsara/claim-particles.yml）。
 * <p>
 * 可配置项：
 * <ul>
 *   <li>边界粒子（昼夜独立）</li>
 *   <li>字体粒子（粒子文字渲染使用的粒子类型）</li>
 *   <li>光束粒子（方向光束使用的粒子类型）</li>
 *   <li>共享显示 / 全图显示开关</li>
 * </ul>
 */
public class ClaimParticleConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ClaimParticleConfig instance;

    /** 昼间边界粒子（默认：smoke） */
    private String dayParticle = "smoke";
    /** 夜间边界粒子（默认：end_rod） */
    private String nightParticle = "end_rod";
    /** 字体粒子（粒子文字渲染，默认：crit） */
    private String fontParticle = "crit";
    /** 光束粒子（方向光束，默认：enchant） */
    private String beamParticle = "enchant";
    /** 共享显示：true=TextDisplay 所有人可见，false=粒子仅持有者可见 */
    private boolean sharedDisplay = false;
    /** 全图显示：true=不在管理区域内也显示边界，false=仅管理区域内显示 */
    private boolean globalDisplay = false;

    /** 粒子名称 → ParticleType 映射 */
    private static final Map<String, ParticleType<?>> PARTICLE_MAP = new HashMap<>();

    /** 可用粒子名称列表（供 brigadier 建议使用） */
    private static final List<String> AVAILABLE_PARTICLE_NAMES = List.of(
            "crit", "enchant", "smoke", "large_smoke", "flame",
            "soul_fire_flame", "end_rod", "happy_villager", "composter",
            "portal", "witch", "cloud", "note", "heart",
            "angry_villager", "snowflake", "ash", "white_ash"
    );

    static {
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

    private ClaimParticleConfig() {
        // 构造器不读文件：load() 由 getInstance() 在实例化后调用
    }

    public static synchronized ClaimParticleConfig getInstance() {
        if (instance == null) {
            instance = new ClaimParticleConfig();
            instance.load();
        }
        return instance;
    }

    // ---- 粒子类型获取 ----

    /** 获取昼间边界粒子类型 */
    public ParticleType<?> getDayParticle() {
        return resolveParticle(dayParticle, ParticleTypes.SMOKE);
    }

    /** 获取夜间边界粒子类型 */
    public ParticleType<?> getNightParticle() {
        return resolveParticle(nightParticle, ParticleTypes.END_ROD);
    }

    /** 获取字体粒子类型（粒子文字渲染） */
    public ParticleType<?> getFontParticle() {
        return resolveParticle(fontParticle, ParticleTypes.CRIT);
    }

    /** 获取光束粒子类型（方向光束） */
    public ParticleType<?> getBeamParticle() {
        return resolveParticle(beamParticle, ParticleTypes.ENCHANT);
    }

    /** 根据时间获取边界粒子类型（dayTime % 24000 < 12000 为白天） */
    public ParticleType<?> getParticleForTime(long dayTime) {
        boolean isDay = (dayTime % 24000) < 12000;
        return isDay ? getDayParticle() : getNightParticle();
    }

    private ParticleType<?> resolveParticle(String name, ParticleType<?> fallback) {
        if (name == null || name.isEmpty()) return fallback;
        ParticleType<?> type = PARTICLE_MAP.get(name.toLowerCase());
        return type != null ? type : fallback;
    }

    // ---- 配置读写 ----

    public String getDayParticleName() { return dayParticle; }
    public String getNightParticleName() { return nightParticle; }
    public String getFontParticleName() { return fontParticle; }
    public String getBeamParticleName() { return beamParticle; }
    public boolean isSharedDisplay() { return sharedDisplay; }
    public boolean isGlobalDisplay() { return globalDisplay; }

    public void setDayParticle(String name) { this.dayParticle = name.toLowerCase(); save(); }
    public void setNightParticle(String name) { this.nightParticle = name.toLowerCase(); save(); }
    public void setFontParticle(String name) { this.fontParticle = name.toLowerCase(); save(); }
    public void setBeamParticle(String name) { this.beamParticle = name.toLowerCase(); save(); }
    public void setSharedDisplay(boolean shared) { this.sharedDisplay = shared; save(); }
    public void setGlobalDisplay(boolean global) { this.globalDisplay = global; save(); }

    // ---- 静态工具 ----

    /** 获取可用粒子名称列表 */
    public static List<String> getAvailableParticleNames() {
        return AVAILABLE_PARTICLE_NAMES;
    }

    /** 获取可用粒子名称（逗号分隔，用于消息显示） */
    public static String getAvailableParticles() {
        return String.join(", ", AVAILABLE_PARTICLE_NAMES);
    }

    /** 检查粒子名称是否有效 */
    public static boolean isValidParticle(String name) {
        return name != null && PARTICLE_MAP.containsKey(name.toLowerCase());
    }

    // ---- 持久化 ----

    private void save() {
        com.shenqianye.samsara.config.YamlStore.SaveResult result =
                com.shenqianye.samsara.config.ConfigManager.getInstance()
                        .save(com.shenqianye.samsara.config.ConfigSchema.CLAIM_PARTICLES,
                                java.util.Map.of(
                                        "dayParticle", dayParticle,
                                        "nightParticle", nightParticle,
                                        "fontParticle", fontParticle,
                                        "beamParticle", beamParticle,
                                        "sharedDisplay", sharedDisplay,
                                        "globalDisplay", globalDisplay));
        if (!result.ok()) {
            Samsara.LOGGER.warn("[samsara] claim-particles.yml 写入失败: {}", result.errors());
        }
    }

    /** 重新读取 YAML（热重载 / Web 保存后）。 */
    public synchronized void load() {
        com.shenqianye.samsara.config.ConfigManager config =
                com.shenqianye.samsara.config.ConfigManager.getInstance();
        String id = com.shenqianye.samsara.config.ConfigSchema.CLAIM_PARTICLES;
        this.dayParticle = config.string(id, "dayParticle");
        this.nightParticle = config.string(id, "nightParticle");
        this.fontParticle = config.string(id, "fontParticle");
        this.beamParticle = config.string(id, "beamParticle");
        this.sharedDisplay = config.bool(id, "sharedDisplay");
        this.globalDisplay = config.bool(id, "globalDisplay");
    }
}
