package com.shenqianye.samsara;

import com.shenqianye.samsara.activity.ActivityRecorder;
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
        // 存储分层：先把旧 config/samsara 整体改名备份，再建新配置目录
        com.shenqianye.samsara.storage.StoragePaths.bootstrapConfig();
        // 配置层（YAML + schema）：权限与命令配置都从这里读
        com.shenqianye.samsara.config.ConfigManager configManager =
                com.shenqianye.samsara.config.ConfigManager.getInstance();
        com.shenqianye.samsara.permission.PermissionConfig.getInstance();
        com.shenqianye.samsara.config.CommandConfig.getInstance();
        configManager.setReloadHook(() -> {
            com.shenqianye.samsara.config.CommandConfig.getInstance().reload();
            com.shenqianye.samsara.permission.PermissionConfig.getInstance().reload();
            com.shenqianye.samsara.claim.ClaimParticleConfig.getInstance().load();
            // 保护范围变化才重算白名单，避免保存其他配置时做无谓的全量几何重建
            if (com.shenqianye.samsara.claim.ClaimRangeConfig.getInstance().load()) {
                com.shenqianye.samsara.claim.AreaService.getInstance().recomputeAll();
            }
            com.shenqianye.samsara.identity.IdentityService.getInstance().reload();
        });
        com.shenqianye.samsara.identity.IdentityService.getInstance().initConfig();
        SeasonCommand.register();
        DebugCommand.register();

        // 旗帜圈地：右键交互 + 粒子预览 + 放置监听 + 拆除监听
        com.shenqianye.samsara.claim.ClaimInteractions.init();
        com.shenqianye.samsara.claim.ClaimParticles.init();
        com.shenqianye.samsara.claim.ClaimPlacementListener.init();
        com.shenqianye.samsara.claim.ClaimBreakListener.init();

        // 活动记录：静默写 JSONL（登录/退出 + 原版统计增量），无命令、无 tick 开销
        ActivityRecorder.init();

        // 宠物多主人：对应驯服物品右键已驯服宠物 → 加入 owner
        com.shenqianye.samsara.pet.PetInteractions.init();

        // 玩家轨迹：5Hz 采样 + 按天轮转 + 几何衰减保留（设计文档 §5.4/§5.5）
        com.shenqianye.samsara.track.TrackRecorder.init();

        // 回看（只读回放）：独立管线，不写 track/diff/cutoffs，结束恢复观看者
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                com.shenqianye.samsara.track.view.ViewManager.getInstance()
                        .onDisconnect(handler.getPlayer().getUUID()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                com.shenqianye.samsara.command.IncompleteCommandHandler
                        .onDisconnect(handler.getPlayer().getUUID()));

        // 回看保护：会话期间取消观看者受到的所有伤害（窒息/溺水/摔落/火焰等）
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register(
                (entity, source, amount) -> !(entity instanceof net.minecraft.server.level.ServerPlayer player
                        && com.shenqianye.samsara.track.view.ViewManager.getInstance()
                        .protectsViewer(player.getUUID())));

        // 差异记录：常开（数据根在 SERVER_STARTING 解析后传入）
        // 时间倒流：调度器（恢复轮 2s；标记/传送/删除/归档 5s 节流）
        // 卸载缓冲区：每 20 tick（1秒）批量处理卸载的待删区块
        final int[] unloadFlushCounter = {0};
        final int[] diffFlushCounter = {0};
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // 防御：玩家操作/爆炸的上下文若因异常未配对，下一 tick 清掉，避免误记世界模拟
            DiffRecorder.clearEditContext();
            RewindEngine.getInstance().tick(server);
            com.shenqianye.samsara.track.TrackReplay.tick(server);
            com.shenqianye.samsara.track.view.ViewManager.getInstance().tick(server);
            com.shenqianye.samsara.identity.IdentityService.getInstance().tick(server);
            com.shenqianye.samsara.shutdown.ShutdownAnnouncer.tick(server);
            com.shenqianye.samsara.command.DebugCommand.onTick();
            if (++unloadFlushCounter[0] >= 20) {
                unloadFlushCounter[0] = 0;
                RewindEngine.getInstance().flushUnloadBuffer(server);
            }
            // 常开窗口每 30 秒落盘一次：崩溃/强杀最多丢 30 秒的方块变化，而不是整个缓冲
            if (++diffFlushCounter[0] >= 600) {
                diffFlushCounter[0] = 0;
                DiffRecorder.flush();
            }
        });

        // 存储分层：SERVER_STARTING 解析存档根并迁移世界数据。
        // 必须在任何世界模块读取路径之前；ConfigManager 已在 mod init 完成 YAML 初始化。
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            com.shenqianye.samsara.storage.StoragePaths.init(server);
            com.shenqianye.samsara.identity.IdentityService.getInstance().init(server);
            SeasonManager.getInstance();
            DiffRecorder.init(com.shenqianye.samsara.storage.StoragePaths.get().worldDataRoot());
            com.shenqianye.samsara.track.TrackRecorder.getInstance()
                    .initRoot(com.shenqianye.samsara.storage.StoragePaths.get().worldDataRoot());
            RewindEngine.getInstance();
        });

        // 待删队列兜底：启动时清空前次遗留（加载中的跳过，卸载即删）
        // 差异记录世界层：服务器起来后才知道世界目录名，先初始化世界层再处理删除队列
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            DiffRecorder.initWorld(server);
            RewindEngine.getInstance().onServerStarted(server);
            com.shenqianye.samsara.track.view.ViewManager.getInstance().onServerStarted(server);
            com.shenqianye.samsara.permission.PermissionCommandPaths.capture(server);
            com.shenqianye.samsara.shutdown.ShutdownAnnouncer.onStarted();
            com.shenqianye.samsara.web.WebLifecycle.start();
        });

        // 待删队列：区块卸载即删（不再等停服）；停服窗口兜底清空 + 归档/保留判定
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
                RewindEngine.getInstance().onChunkUnload(level, chunk));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            com.shenqianye.samsara.track.view.ViewManager.getInstance().shutdown(server);
            com.shenqianye.samsara.track.TrackReplay.shutdown();
            RewindEngine.getInstance().onServerStopped(server);
        });

        // 身份：账号切换同步先应用玩家数据，再让分组/待传送把玩家放到本周目位置
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                com.shenqianye.samsara.identity.IdentityService.getInstance()
                        .onJoin(handler.getPlayer()));
        // 分组出生：倒流完成后的离线玩家上线补传送（放在身份同步之后）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                SeasonManager.getInstance().onPlayerJoin(server, handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                com.shenqianye.samsara.identity.IdentityService.getInstance()
                        .onDisconnect(handler.getPlayer()));

        // 优雅停服：先停 Web、同步身份、恢复回看、刷盘，最后发退出界面提示
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            com.shenqianye.samsara.web.WebLifecycle.stop(); // 先停 Web，避免请求在停服窗口写配置
            // 身份全状态同步必须在服务器保存玩家数据之前执行
            com.shenqianye.samsara.identity.IdentityService.getInstance().onServerStopping(server);
            com.shenqianye.samsara.track.view.ViewManager.getInstance().shutdown(server); // 先恢复观看者，再让服务器保存
            com.shenqianye.samsara.track.TrackReplay.shutdown(); // 先移除回放人偶，避免写进存档
            DiffRecorder.flush(); // 停服前把常开窗口的缓冲落盘（不依赖后续 SERVER_STOPPED 的时序）
            DiffRecorder.clearEditContext();
            com.shenqianye.samsara.claim.ClaimParticles.shutdown();
            // 最后断开玩家：connection.disconnect 会把玩家从在线列表移除并保存其数据
            com.shenqianye.samsara.shutdown.ShutdownAnnouncer.onStopping(server);
        });
    }
}