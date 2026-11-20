package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shenqianye.samsara.command.ChatFormat;
import com.shenqianye.samsara.command.ui.ChatUi;
import com.shenqianye.samsara.config.CommandConfig;
import com.shenqianye.samsara.shutdown.ShutdownAnnouncer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;

/** Samsara 帮助面板与 restart 命令。 */
final class DebugHelpCommand {

    private DebugHelpCommand() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> helpNode() {
        return Commands.literal("help")
                .executes(ctx -> send(ctx.getSource()));
    }

    static LiteralArgumentBuilder<CommandSourceStack> restartNode() {
        return Commands.literal("restart")
                .requires(src -> com.shenqianye.samsara.permission.PermissionChecker
                        .check(src, "restart"))
                .executes(ctx -> restart(ctx.getSource(),
                        CommandConfig.getInstance().shutdownCountdownSeconds()))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
                        .executes(ctx -> restart(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "seconds"))));
    }
    static int send(CommandSourceStack src) {
        String head = ChatFormat.getFirstPrefix();
        String cont = ChatFormat.getContinuePrefix();
        ChatUi.send(src,
                ChatUi.text(head + "指令列表（青色=点击执行 · 黄色=点击填入，不执行）", ChatFormatting.WHITE),
                ChatUi.text(cont + "玩家：", ChatFormatting.GRAY),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.run("版本", "/samsara version", "查看模组与游戏版本"),
                        ChatUi.run("MSPT", "/samsara mspt", "查看服务器 MSPT"),
                        ChatUi.run("TPS", "/samsara tps", "查看服务器 TPS")),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.run("我的位置", "/samsara where", "查看自己的维度、坐标、区块与朝向"),
                        ChatUi.run("世界栈", "/samsara world list", "查看栈底→栈顶与当前世界"),
                        ChatUi.run("出生点", "/samsara spawn", "当前世界 / 当前轮 / 出生点"),
                        ChatUi.run("出生点历史", "/samsara spawn history", "查看同世界历次出生点"),
                        ChatUi.run("圈地信息", "/samsara claim info", "查看当前区块的圈地信息"),
                        ChatUi.run("边界开关", "/samsara claim boundary", "切换边界持续显示")),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.run("身份状态", "/samsara identity status", "查看自己的身份组与 data UUID")),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.suggest("发起开新周目", "/samsara season advance ",
                                "无管理员且在线 >1 人时发起投票；填入 same 或 new"),
                        ChatUi.run("同意开新周目", "/samsara season agree", "同意当前进行中的投票"),
                        ChatUi.run("取消/撤回投票", "/samsara season cancel",
                                "发起者撤回，或管理员取消进行中的投票"),
                        ChatUi.run("投票状态", "/samsara season vote-status",
                                "查看发起者、模式、同意名单与剩余时间")),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.suggest("回看时段", "/samsara track list ", "填入 /samsara track list <玩家>"),
                        ChatUi.suggest("片段收藏", "/samsara track clip add ",
                                "填入 /samsara track clip add <目标> <开始时间> --duration 30m"),
                        ChatUi.run("收藏列表", "/samsara track clip list", "查看自己的收藏片段"),
                        ChatUi.suggest("向玩家传送", "/samsara tpa ", "填入 /samsara tpa <玩家>"),
                        ChatUi.suggest("请玩家过来", "/samsara tpahere ", "填入 /samsara tpahere <玩家>")),
                ChatUi.text(cont + "管理员（点击只填入，仍受权限检查）：", ChatFormatting.GRAY),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.suggestIfPermitted(src, "reset same", "同世界重置",
                                "/samsara reset same", "倒流删未保护区 + 排斥点重生（需二次确认）"),
                        ChatUi.suggestIfPermitted(src, "reset new", "新世界入栈",
                                "/samsara reset new ", "填入 /samsara reset new [种子]（会停服）"),
                        ChatUi.suggestIfPermitted(src, "world enter", "进入旧世界",
                                "/samsara world enter ", "填入 /samsara world enter <世界名>（会停服）"),
                        ChatUi.suggestIfPermitted(src, "world leave", "返回下层",
                                "/samsara world leave", "返回世界栈下一层（会停服）")),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.suggestIf(isOwner(src), "调试状态",
                                "/samsara debug status", "查看调试总览"),
                        ChatUi.suggestIf(isOwner(src), "圈地管理",
                                "/samsara debug claim list", "查看全部圈地旗帜"),
                        ChatUi.suggestIf(isOwner(src), "权限配置",
                                "/samsara debug perm list", "查看命令权限等级"),
                        ChatUi.suggestIf(isOwner(src), "身份映射",
                                "/samsara debug identity map", "查看全部身份组"),
                        ChatUi.suggestIf(isOwner(src), "身份审计",
                                "/samsara debug identity audit", "检查重复 UUID、空组、非法 data"),
                        ChatUi.suggestIf(isOwner(src), "倒流预览",
                                "/samsara debug rewind preview", "预览将倒流的区块（不执行）")),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.suggestIfPermitted(src, "camera start", "镜头回看",
                                "/samsara camera start ", "轨迹 + Diff 同时回看；--no-track/--no-diff 可单独关闭"),
                        ChatUi.suggestIfPermitted(src, "camera status", "镜头状态",
                                "/samsara camera status", "查看轨迹与 Diff 的进度/暂停/倍速"),
                        ChatUi.suggestIfPermitted(src, "camera stop", "停止镜头",
                                "/samsara camera stop", "同时清理轨迹人偶与 Diff HUD")),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.suggestIf(isOwner(src), "轨迹回放",
                                "/samsara debug track replay ", "填入 /samsara debug track replay <玩家> <开始时刻>"),
                        ChatUi.suggestIf(isOwner(src), "轨迹回看",
                                "/samsara track view ", "填入 /samsara track view <玩家> <开始时间>"),
                        ChatUi.suggestIf(isOwner(src), "Diff 回看",
                                "/samsara diff view ", "填入 /samsara diff view [--from ... --to ...]"),
                        ChatUi.suggestIf(isOwner(src), "全部调试命令",
                                "/samsara debug ", "点击填入后自行补参数")),
                ChatUi.text(cont + "完整命令树：输入 /samsara <Tab> 逐级补全；玩家名、周目、时长、回看参数均支持 Tab。",
                        ChatFormatting.GRAY));
        return 1;
    }

    /** /samsara version：从 FabricLoader 取模组版本 + 显示名 + 游戏版本 */

    private static int restart(CommandSourceStack src, int seconds) {
        if (!com.shenqianye.samsara.shutdown.ShutdownAnnouncer.request(
                src.getServer(), seconds)) {
            return DebugCommand.reply(src, "已有正在进行的关服倒计时");
        }
        return 1;
    }


    private static boolean isOwner(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }
}


