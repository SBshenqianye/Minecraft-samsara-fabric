package com.shenqianye.samsara.web;

import com.shenqianye.samsara.season.SeasonAdvanceVoteManager;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 全员同意制只读 Web 数据源。
 *
 * <p>不提供取消/同意等写操作：游戏内命令才是投票入口，Web 只展示状态。
 */
public final class SeasonAdvanceWebService {
    private final Supplier<Map<String, Object>> modelSupplier;

    public SeasonAdvanceWebService(MinecraftServer server) {
        this(() -> SeasonAdvanceVoteManager.getInstance().webModel(server));
    }

    /** 测试用：允许注入固定快照。 */
    SeasonAdvanceWebService(Supplier<Map<String, Object>> modelSupplier) {
        this.modelSupplier = modelSupplier;
    }

    public Map<String, Object> model() {
        return modelSupplier.get();
    }
}
