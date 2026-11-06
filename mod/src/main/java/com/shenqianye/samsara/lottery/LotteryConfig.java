package com.shenqianye.samsara.lottery;

import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * 死人乐透配置（{@code config/samsara/lottery.yml}）。
 *
 * <p>配置项全部由 {@link ConfigSchema} 注册；本类只缓存热重载后的值。
 */
public final class LotteryConfig {
    private static LotteryConfig instance;

    private boolean enabled;
    private String mode = "game";
    private String drawTime = "07:00";
    private String cutoffTime = "00:00";
    private boolean countPlayers = true;
    private boolean countVillagers = true;
    private boolean countWanderingTraders = true;
    private boolean countNamedMobs = true;
    private boolean repeatDeaths = true;
    private java.util.List<String> excludeNames = java.util.List.of();
    private boolean revealLiveCount;
    private double ticketPrice = 8.0;
    private int maxTicketsPerPlayer = 3;
    private int maxTicketsPerRound = 128;
    private boolean overpayToPot = true;
    private double rakePercent = 5.0;
    private String rakeDestination = "fund";
    private boolean rakeOnlyOnPayout = true;
    private String splitMode = "ticket";
    private String noWinner = "rollover";
    private int maxPotValue = 4096;
    private boolean seedEnabled = true;
    private double seedPercent = 10.0;
    private double seedMaxValue = 512.0;
    private double seedMinFundValue = 0.0;
    private boolean goldSyncEnabled = true;
    private String goldSourceUrl = "https://api.gold-api.com/price/XAU";
    private double goldBaseBlockValue = 36.0;
    private double goldReferenceUsdPerOunce = 4000.0;
    private double goldMinFactor = 0.5;
    private double goldMaxFactor = 2.0;
    private int goldSyncIntervalMinutes = 360;
    private boolean goldCraftLink;
    private boolean audit = true;
    private boolean publicVisible = true;

    private LotteryConfig() {
        reload();
    }

    public static synchronized LotteryConfig getInstance() {
        if (instance == null) {
            instance = new LotteryConfig();
        }
        return instance;
    }

    public synchronized String reload() {
        ConfigManager c = ConfigManager.getInstance();
        String id = ConfigSchema.LOTTERY;
        enabled = c.bool(id, "enabled");
        mode = c.string(id, "day.mode");
        drawTime = c.string(id, "day.drawTime");
        cutoffTime = c.string(id, "day.cutoffTime");
        countPlayers = c.bool(id, "count.players");
        countVillagers = c.bool(id, "count.villagers");
        countWanderingTraders = c.bool(id, "count.wanderingTraders");
        countNamedMobs = c.bool(id, "count.namedMobs");
        repeatDeaths = c.bool(id, "count.repeatDeaths");
        excludeNames = c.stringList(id, "count.excludeNames");
        revealLiveCount = c.bool(id, "count.revealLiveCount");
        ticketPrice = Math.max(0.0, c.decimal(id, "bet.price"));
        maxTicketsPerPlayer = Math.max(1, c.integer(id, "bet.maxTicketsPerPlayer"));
        maxTicketsPerRound = Math.max(1, c.integer(id, "bet.maxTicketsPerRound"));
        overpayToPot = !"reject".equalsIgnoreCase(c.string(id, "bet.overpay"));
        rakePercent = Math.max(0.0, Math.min(100.0, c.decimal(id, "rake.percent")));
        rakeDestination = c.string(id, "rake.destination");
        rakeOnlyOnPayout = c.bool(id, "rake.onlyOnPayout");
        splitMode = c.string(id, "settlement.split");
        noWinner = c.string(id, "settlement.noWinner");
        maxPotValue = Math.max(1, c.integer(id, "settlement.maxPotValue"));
        seedEnabled = c.bool(id, "seed.enabled");
        seedPercent = Math.max(0.0, Math.min(100.0, c.decimal(id, "seed.percent")));
        seedMaxValue = Math.max(0.0, c.decimal(id, "seed.maxValue"));
        seedMinFundValue = Math.max(0.0, c.decimal(id, "seed.minFundValue"));
        goldSyncEnabled = c.bool(id, "gold.syncEnabled");
        goldSourceUrl = c.string(id, "gold.sourceUrl");
        goldBaseBlockValue = Math.max(0.0, c.decimal(id, "gold.baseBlockValue"));
        goldReferenceUsdPerOunce = Math.max(0.01, c.decimal(id, "gold.referenceUsdPerOunce"));
        goldMinFactor = Math.max(0.01, Math.min(100.0, c.decimal(id, "gold.minFactor")));
        goldMaxFactor = Math.max(goldMinFactor,
                Math.min(100.0, c.decimal(id, "gold.maxFactor")));
        goldSyncIntervalMinutes = Math.max(1, c.integer(id, "gold.syncIntervalMinutes"));
        goldCraftLink = c.bool(id, "gold.craftLink");
        audit = c.bool(id, "audit.enabled");
        publicVisible = c.bool(id, "web.publicVisible");
        return "乐透配置已重载：" + (enabled ? "启用" : "禁用")
                + " · " + mode + " 日 · " + drawTime + " 开奖";
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean realDay() {
        return "real".equalsIgnoreCase(mode);
    }

    public String drawTime() {
        return drawTime;
    }

    public LocalTime drawLocalTime() {
        return parseTime(drawTime, LocalTime.of(7, 0));
    }

    public String cutoffTime() {
        return cutoffTime;
    }

    public LocalTime cutoffLocalTime() {
        return parseTime(cutoffTime, LocalTime.MIDNIGHT);
    }

    public boolean countPlayers() {
        return countPlayers;
    }

    public boolean countVillagers() {
        return countVillagers;
    }

    public boolean countWanderingTraders() {
        return countWanderingTraders;
    }

    public boolean countNamedMobs() {
        return countNamedMobs;
    }

    public boolean repeatDeaths() {
        return repeatDeaths;
    }

    public java.util.List<String> excludeNames() {
        return excludeNames;
    }

    public boolean revealLiveCount() {
        return revealLiveCount;
    }

    public double ticketPrice() {
        return ticketPrice;
    }

    public int maxTicketsPerPlayer() {
        return maxTicketsPerPlayer;
    }

    public int maxTicketsPerRound() {
        return maxTicketsPerRound;
    }

    public boolean overpayToPot() {
        return overpayToPot;
    }

    public double rakePercent() {
        return rakePercent;
    }

    public String rakeDestination() {
        return rakeDestination;
    }

    public boolean rakeOnlyOnPayout() {
        return rakeOnlyOnPayout;
    }

    public String splitMode() {
        return splitMode;
    }

    public String noWinner() {
        return noWinner;
    }

    public int maxPotValue() {
        return maxPotValue;
    }

    public boolean seedEnabled() {
        return seedEnabled;
    }

    public double seedPercent() {
        return seedPercent;
    }

    public double seedMaxValue() {
        return seedMaxValue;
    }

    public double seedMinFundValue() {
        return seedMinFundValue;
    }

    public boolean goldSyncEnabled() {
        return goldSyncEnabled;
    }

    public String goldSourceUrl() {
        return goldSourceUrl;
    }

    public double goldBaseBlockValue() {
        return goldBaseBlockValue;
    }

    public double goldReferenceUsdPerOunce() {
        return goldReferenceUsdPerOunce;
    }

    public double goldMinFactor() {
        return goldMinFactor;
    }

    public double goldMaxFactor() {
        return goldMaxFactor;
    }

    public int goldSyncIntervalMinutes() {
        return goldSyncIntervalMinutes;
    }

    public boolean goldCraftLink() {
        return goldCraftLink;
    }

    public boolean audit() {
        return audit;
    }

    public boolean publicVisible() {
        return publicVisible;
    }

    public String configPath() {
        return ConfigManager.getInstance().file(ConfigSchema.LOTTERY)
                .toAbsolutePath().normalize().toString();
    }

    private static LocalTime parseTime(String text, LocalTime fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return LocalTime.parse(text.trim());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
