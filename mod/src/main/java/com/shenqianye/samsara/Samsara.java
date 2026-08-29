package com.shenqianye.samsara;

import com.shenqianye.samsara.activity.ActivityManager;
import com.shenqianye.samsara.command.DebugCommand;
import com.shenqianye.samsara.command.SeasonCommand;
import com.shenqianye.samsara.rewind.DiffRecorder;
import com.shenqianye.samsara.rewind.RewindEngine;
import com.shenqianye.samsara.season.SeasonManager;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
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
 * 时间倒流钩子：END_SERVER_TICK 驱动倒流调度；SERVER_STOPPED 执行倒流物理删除
 * （停服窗口，世界已保存、区域文件已关闭，遵守"仅维护窗口删区块"原则）；
 * JOIN 处理倒流完成后的离线玩家补传送。
 */
public class Samsara implements DedicatedServerModInitializer {
    public static final String MOD_ID = "samsara";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeServer() {
        LOGGER.info("[周目管理 samsara] 已加载 —— 空间/时间/逆熵，世界生命周期管理");
        // 权限系统初始化（必须在命令注册前）
        com.shenqianye.samsara.permission.PermissionConfig.getInstance();
        SeasonCommand.register();
        DebugCommand.register();

        // 旗帜圈地：右键交互 + 粒子预览 + 放置监听 + 拆除监听
        com.shenqianye.samsara.claim.ClaimInteractions.init();
        com.shenqianye.samsara.claim.ClaimParticles.init();
        com.shenqianye.samsara.claim.ClaimPlacementListener.init();
        com.shenqianye.samsara.claim.ClaimBreakListener.init();

        // 差异记录：常开（diff/current 窗口；倒流开始冻结为恢复源）
        DiffRecorder.init(net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve("samsara"));

        // 时间倒流：调度器（内部 5s 节流：标记/恢复/传送/删除/归档）
        // 卸载缓冲区：每 20 tick（1秒）批量处理卸载的待删区块
        final int[] unloadFlushCounter = {0};
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RewindEngine.getInstance().tick(server);
            com.shenqianye.samsara.command.DebugCommand.onTick();
            if (++unloadFlushCounter[0] >= 20) {
                unloadFlushCounter[0] = 0;
                RewindEngine.getInstance().flushUnloadBuffer(server);
            }
        });

        // 待删队列兜底：启动时清空前次遗留（加载中的跳过，卸载即删）
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RewindEngine.getInstance().onServerStarted(server));

        // 待删队列：区块卸载即删（不再等停服）；停服窗口兜底清空 + 归档/保留判定
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
                RewindEngine.getInstance().onChunkUnload(level, chunk));
        ServerLifecycleEvents.SERVER_STOPPED.register(server ->
                RewindEngine.getInstance().onServerStopped(server));

        // 分组出生：倒流完成后的离线玩家上线补传送
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            SeasonManager.getInstance().onPlayerJoin(server, handler.getPlayer());
            // 活跃度：上线事件实时追加 activity.log
            ActivityManager.getInstance().onJoin(handler.getPlayer().getGameProfile().name());
        });
        // 活跃度：下线事件实时追加 activity.log
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ActivityManager.getInstance().onLeave(handler.getPlayer().getGameProfile().name()));

        // 优雅停服：停止发送粒子包，发告别消息（减少客户端断连时的渲染压力）
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            com.shenqianye.samsara.claim.ClaimParticles.shutdown();
            for (var player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(
                        com.shenqianye.samsara.command.ChatFormat.formatComponent(
                                "服务器即将关闭，请稍候…"));
            }
        });
    }
}