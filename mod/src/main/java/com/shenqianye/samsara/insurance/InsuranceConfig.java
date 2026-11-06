package com.shenqianye.samsara.insurance;

import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;

/** 保险配置（{@code config/samsara/insurance.yml}）。 */
public final class InsuranceConfig {
    private static InsuranceConfig instance;
    private boolean enabled;
    private double premiumMultiplier = 2.0;
    private int policyDays = 30;
    private int claimCooldownMinutes = 60;
    private int maxClaimsPerDay = 3;
    private double largeClaimValue = 512.0;
    private boolean allowStackable;
    private int scanRadius = 128;
    private boolean scanOfflinePlayers = true;
    private boolean scanOtherDimensions = true;
    private boolean useContainerIndex = true;
    private boolean entitiesEnabled;

    private InsuranceConfig() {
        reload();
    }

    public static synchronized InsuranceConfig getInstance() {
        if (instance == null) instance = new InsuranceConfig();
        return instance;
    }

    public synchronized String reload() {
        ConfigManager c = ConfigManager.getInstance();
        String id = ConfigSchema.INSURANCE;
        enabled = c.bool(id, "enabled");
        premiumMultiplier = Math.max(0.0, c.decimal(id, "premiumMultiplier"));
        policyDays = Math.max(1, c.integer(id, "policyDays"));
        claimCooldownMinutes = Math.max(0, c.integer(id, "claimCooldownMinutes"));
        maxClaimsPerDay = Math.max(1, c.integer(id, "maxClaimsPerDay"));
        largeClaimValue = Math.max(0.0, c.decimal(id, "largeClaimValue"));
        allowStackable = c.bool(id, "allowStackable");
        scanRadius = Math.max(16, c.integer(id, "scanRadius"));
        scanOfflinePlayers = c.bool(id, "scanOfflinePlayers");
        scanOtherDimensions = c.bool(id, "scanOtherDimensions");
        useContainerIndex = c.bool(id, "useContainerIndex");
        entitiesEnabled = c.bool(id, "entities.enabled");
        return "保险配置已重载：" + (enabled ? "启用" : "禁用");
    }

    public boolean enabled() { return enabled; }
    public double premiumMultiplier() { return premiumMultiplier; }
    public int policyDays() { return policyDays; }
    public int claimCooldownMinutes() { return claimCooldownMinutes; }
    public int maxClaimsPerDay() { return maxClaimsPerDay; }
    public double largeClaimValue() { return largeClaimValue; }
    public boolean allowStackable() { return allowStackable; }
    public int scanRadius() { return scanRadius; }
    public boolean scanOfflinePlayers() { return scanOfflinePlayers; }
    public boolean scanOtherDimensions() { return scanOtherDimensions; }
    public boolean useContainerIndex() { return useContainerIndex; }
    public boolean entitiesEnabled() { return entitiesEnabled; }
}
