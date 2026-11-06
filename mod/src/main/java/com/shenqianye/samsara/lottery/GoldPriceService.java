package com.shenqianye.samsara.lottery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.storage.StoragePaths;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * 国际金价缓存与同步。
 *
 * <p>只把金价当作“指数”使用：金块仍是 1 VP 体系里的锚定价值，按国际金价相对
 * {@code gold.referenceUsdPerOunce} 的变化缩放；不会直接用 1 m³ 黄金的真实质量换算成 VP。
 */
public final class GoldPriceService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static GoldPriceService instance;

    private volatile double price;
    private volatile long updatedAt;
    private volatile String source = "";
    private volatile long lastAttempt;
    private volatile boolean fetching;
    private volatile Path cachePath;

    private GoldPriceService() {
    }

    public static synchronized GoldPriceService getInstance() {
        if (instance == null) {
            instance = new GoldPriceService();
        }
        return instance;
    }

    public synchronized void init() {
        cachePath = StoragePaths.configRoot().resolve("gold-price.json");
        load();
    }

    public double currentUsdPerOunce() {
        double current = price;
        if (current > 0.0) {
            return current;
        }
        return Math.max(0.01, LotteryConfig.getInstance().goldReferenceUsdPerOunce());
    }

    /** 当前金块 VP；未启用动态金价时返回 -1。 */
    public double blockValue() {
        LotteryConfig config = LotteryConfig.getInstance();
        if (!config.goldSyncEnabled()) {
            return -1.0;
        }
        double reference = Math.max(0.01, config.goldReferenceUsdPerOunce());
        double factor = Math.max(config.goldMinFactor(),
                Math.min(config.goldMaxFactor(), currentUsdPerOunce() / reference));
        return Math.max(0.0, config.goldBaseBlockValue() * factor);
    }

    public long updatedAt() {
        return updatedAt;
    }

    public String source() {
        return source;
    }

    public void tick() {
        LotteryConfig config = LotteryConfig.getInstance();
        if (!config.goldSyncEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long interval = Math.max(1, config.goldSyncIntervalMinutes()) * 60_000L;
        if (now - lastAttempt < interval || fetching) {
            return;
        }
        lastAttempt = now;
        fetchAsync(config.goldSourceUrl());
    }

    public synchronized void setManualPrice(double value, String from) {
        if (value <= 0.0) {
            return;
        }
        price = value;
        updatedAt = System.currentTimeMillis();
        source = from == null || from.isBlank() ? "manual" : from;
        save();
    }

    private void fetchAsync(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        fetching = true;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpClient.newHttpClient()
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() / 100 != 2) {
                            Samsara.LOGGER.warn("[gold] 金价接口返回 HTTP {}", response.statusCode());
                            return;
                        }
                        try {
                            JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
                            double value = object.get("price").getAsDouble();
                            if (value > 0.0) {
                                setManualPrice(value, url);
                                Samsara.LOGGER.info("[gold] 金价已同步：{} USD/oz", value);
                            }
                        } catch (RuntimeException e) {
                            Samsara.LOGGER.warn("[gold] 解析金价失败：{}", e.getMessage());
                        }
                    })
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            Samsara.LOGGER.warn("[gold] 拉取金价失败：{}", error.getMessage());
                        }
                        fetching = false;
                    });
        } catch (RuntimeException e) {
            fetching = false;
            Samsara.LOGGER.warn("[gold] 金价请求创建失败：{}", e.getMessage());
        }
    }

    private void load() {
        Path path = cachePath;
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonObject object = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            price = object.has("price") ? object.get("price").getAsDouble() : 0.0;
            updatedAt = object.has("updatedAt") ? object.get("updatedAt").getAsLong() : 0L;
            source = object.has("source") ? object.get("source").getAsString() : "cache";
        } catch (IOException | RuntimeException e) {
            Samsara.LOGGER.warn("[gold] 读取金价缓存失败：{}", e.getMessage());
        }
    }

    private synchronized void save() {
        Path path = cachePath;
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            JsonObject object = new JsonObject();
            object.addProperty("price", price);
            object.addProperty("updatedAt", updatedAt);
            object.addProperty("source", source);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(object), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[gold] 写入金价缓存失败：{}", e.getMessage());
        }
    }
}
