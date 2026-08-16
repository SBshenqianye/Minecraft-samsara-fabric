package com.shenqianye.samsara;

import com.shenqianye.samsara.command.DebugCommand;
import com.shenqianye.samsara.command.SeasonCommand;
import com.shenqianye.samsara.rewind.RewindEngine;
import com.shenqianye.samsara.season.SeasonManager;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * samsara（轮回）主入口 —— 服务端模组。
 * <p>
 * 设计为 <b>server entrypoint + environment "*"</b>（见 fabric.mod.json）：
 * <ul>
 *   <li><b>纯服务器</b>：只装本模组即获得全部功能（周目状态机/分组传送/时间倒流等均为服务端逻辑）；</li>
 *   <li><b>客户端安装 + 单人存档</b>：fabric-loader 的 EntrypointPatch 会在 integrated server
 *       （单人=客户端进程内的逻辑服务器）启动时调用 server entrypoint，功能同样可用；</li>
 *   <li><b>客户端安装 + 连接他人服务器</b>：模组被加载但 server entrypoint 不执行（无逻辑服务器），
 *       零副作用 —— 功能由服务器端提供，客户端无需安装。</li>
 * </ul>
 * <p>
 * M2 钩子：END_SERVER_TICK 驱动倒流调度；SERVER_STOPPED 执行倒流物理删除
 * （停服窗口，世界已保存、区域文件已关闭，遵守"仅维护窗口删区块"§10）；
 * JOIN 处理倒流完成后的离线玩家补传送。
 */
public class Samsara implements DedicatedServerModInitializer {
    public static final String MOD_ID = "samsara";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeServer() {
        LOGGER.info("[周目管理 samsara] 已加载 —— 空间/时间/逆熵，世界生命周期管理");
        SeasonCommand.register();
        DebugCommand.register();

        // M2 时间倒流：调度器（内部 5s 节流）
        ServerTickEvents.END_SERVER_TICK.register(server ->
                RewindEngine.getInstance().tick(server));

        // M2 时间倒流：物理删除在停服窗口执行（备份 + 清零区块槽）
        ServerLifecycleEvents.SERVER_STOPPED.register(server ->
                RewindEngine.getInstance().applyPhysicalDeletion(server));

        // M2 分组出生：倒流完成后的离线玩家上线补传送
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                SeasonManager.getInstance().onPlayerJoin(server, handler.getPlayer()));
    }
}