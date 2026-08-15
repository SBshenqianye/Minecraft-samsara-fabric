package com.shenqianye.samsara;

import com.shenqianye.samsara.command.DebugCommand;
import com.shenqianye.samsara.command.SeasonCommand;
import net.fabricmc.api.DedicatedServerModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * samsara（轮回）主入口 —— 服务端模组。
 * <p>
 * 设计为 <b>server entrypoint + environment "*"</b>（见 fabric.mod.json）：
 * <ul>
 *   <li><b>纯服务器</b>：只装本模组即获得全部功能（周目状态机/传送/区块管理等均为服务端逻辑）；</li>
 *   <li><b>客户端安装 + 单人存档</b>：fabric-loader 的 EntrypointPatch 会在 integrated server
 *       （单人=客户端进程内的逻辑服务器）启动时调用 server entrypoint，功能同样可用；</li>
 *   <li><b>客户端安装 + 连接他人服务器</b>：模组被加载但 server entrypoint 不执行（无逻辑服务器），
 *       零副作用 —— 功能由服务器端提供，客户端无需安装。</li>
 * </ul>
 */
public class Samsara implements DedicatedServerModInitializer {
    public static final String MOD_ID = "samsara";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeServer() {
        LOGGER.info("[周目管理 samsara] 已加载 —— 空间/时间/逆熵，世界生命周期管理");
        SeasonCommand.register();
        DebugCommand.register();
    }
}
