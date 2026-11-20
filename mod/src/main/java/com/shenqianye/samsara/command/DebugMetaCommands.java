package com.shenqianye.samsara.command;

import com.mojang.brigadier.CommandDispatcher;
import com.shenqianye.samsara.command.ui.ChatUi;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shenqianye.samsara.output.OutputRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.LinkedHashMap;
import java.util.Map;

/** `/samsara version|mspt|tps` 与 tick 指标。 */
public final class DebugMetaCommands {

    private static long lastTickNanos = System.nanoTime();
    private static double cachedMspt = 50.0;

    private DebugMetaCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> versionNode() {
        return Commands.literal("version")
                .executes(ctx -> replyVersion(ctx.getSource()));
    }

    static LiteralArgumentBuilder<CommandSourceStack> msptNode() {
        return Commands.literal("mspt")
                .executes(ctx -> DebugCommand.reply(ctx.getSource(), mspt()));
    }

    static LiteralArgumentBuilder<CommandSourceStack> tpsNode() {
        return Commands.literal("tps")
                .executes(ctx -> DebugCommand.reply(ctx.getSource(), tps()));
    }

    /** 由 Samsara.onTick() 每 tick 调用。 */
    public static void onTick() {
        long now = System.nanoTime();
        cachedMspt = (now - lastTickNanos) / 1_000_000.0;
        lastTickNanos = now;
    }

    /** 条件别名：/mspt 和 /tps（仅当其他模组未注册时）。 */
    static void registerAliases(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (dispatcher.getRoot().getChild("mspt") == null) {
            dispatcher.register(msptNode());
            CommandAliases.markRoot("mspt");
        }
        if (dispatcher.getRoot().getChild("tps") == null) {
            dispatcher.register(tpsNode());
            CommandAliases.markRoot("tps");
        }
    }

    private static int replyVersion(CommandSourceStack src) {
        ChatUi.send(src, OutputRenderer.render("version", versionValues(), src, null));
        return 1;
    }

    private static Map<String, Object> versionValues() {
        String version = "unknown";
        String name = "samsara";
        String gameVersion = "unknown";
        try {
            var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            var container = loader.getModContainer("samsara");
            if (container.isPresent()) {
                version = container.get().getMetadata().getVersion().getFriendlyString();
                name = container.get().getMetadata().getName();
            }
            var minecraft = loader.getModContainer("minecraft");
            if (minecraft.isPresent()) {
                gameVersion = minecraft.get().getMetadata().getVersion().getFriendlyString();
            }
        } catch (Exception e) {
            // 取不到版本就用 unknown
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name);
        values.put("version", version);
        values.put("minecraft", gameVersion);
        return values;
    }

    private static String mspt() {
        String color = cachedMspt < 40 ? "§a" : cachedMspt < 50 ? "§e" : "§c";
        return "MSPT: " + color + String.format("%.1f", cachedMspt) + "ms§r"
                + "（目标 50ms = 20 TPS）";
    }

    private static String tps() {
        double tps = Math.min(20.0, 1000.0 / Math.max(cachedMspt, 0.1));
        String color = tps >= 19.5 ? "§a" : tps >= 15.0 ? "§e" : "§c";
        return "TPS: " + color + String.format("%.1f", tps) + "§r"
                + "（MSPT=" + String.format("%.1f", cachedMspt) + "ms）";
    }
}

