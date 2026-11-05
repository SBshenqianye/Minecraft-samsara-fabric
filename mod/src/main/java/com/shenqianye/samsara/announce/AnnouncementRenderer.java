package com.shenqianye.samsara.announce;

import com.shenqianye.samsara.permission.PermissionChecker;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 公告文本渲染：变量、传统颜色码、十六进制颜色、点击/悬浮事件。 */
public final class AnnouncementRenderer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private AnnouncementRenderer() {
    }

    public static List<MutableComponent> chatLines(Announcement a, ServerPlayer player,
                                                   MinecraftServer server) {
        List<MutableComponent> out = new ArrayList<>();
        for (String line : substitute(a.chatText, player, server).split("\\n", -1)) {
            out.add(component(line, a, player, server));
        }
        return out;
    }

    public static MutableComponent component(String text, Announcement a,
                                             ServerPlayer player, MinecraftServer server) {
        String value = substitute(text, player, server);
        Style clickStyle = clickStyle(a, player);
        Style hoverStyle = hoverStyle(a, player, server);
        MutableComponent root = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '&' || i + 1 >= value.length()) {
                literal.append(c);
                continue;
            }
            char next = value.charAt(i + 1);
            if (next == '#' && i + 7 < value.length()
                    && value.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) {
                flush(root, literal, style, clickStyle, hoverStyle);
                int rgb = Integer.parseInt(value.substring(i + 2, i + 8), 16);
                style = style.withColor(TextColor.fromRgb(rgb));
                i += 7;
                continue;
            }
            ChatFormatting formatting = ChatFormatting.getByCode(next);
            if (formatting != null) {
                flush(root, literal, style, clickStyle, hoverStyle);
                style = formatting == ChatFormatting.RESET ? Style.EMPTY
                        : formatting.ordinal() < ChatFormatting.OBFUSCATED.ordinal()
                        ? style.withColor(formatting)
                        : style.applyFormat(formatting);
                i++;
                continue;
            }
            literal.append(c);
        }
        flush(root, literal, style, clickStyle, hoverStyle);
        return root;
    }

    /** Web 预览用：变量替换后保留颜色码，便于前端自行上色。 */
    public static String preview(String text, Announcement a, ServerPlayer player,
                                 MinecraftServer server) {
        return substitute(text, player, server);
    }

    private static void flush(MutableComponent root, StringBuilder literal, Style style,
                              Style clickStyle, Style hoverStyle) {
        if (literal.length() == 0) {
            return;
        }
        Style finalStyle = style
                .withClickEvent(clickStyle.getClickEvent())
                .withHoverEvent(hoverStyle.getHoverEvent());
        root.append(Component.literal(literal.toString()).setStyle(finalStyle));
        literal.setLength(0);
    }

    private static Style clickStyle(Announcement a, ServerPlayer player) {
        if (a.clickAction == null || "none".equals(a.clickAction) || a.clickValue.isBlank()) {
            return Style.EMPTY;
        }
        if ("run".equals(a.clickAction) && !canRunClick(a, player)) {
            return Style.EMPTY;
        }
        try {
            ClickEvent event = switch (a.clickAction) {
                case "run" -> new ClickEvent.RunCommand(a.clickValue);
                case "suggest" -> new ClickEvent.SuggestCommand(a.clickValue);
                case "open_url" -> new ClickEvent.OpenUrl(URI.create(a.clickValue));
                case "copy" -> new ClickEvent.CopyToClipboard(a.clickValue);
                default -> null;
            };
            return event == null ? Style.EMPTY : Style.EMPTY.withClickEvent(event);
        } catch (RuntimeException e) {
            return Style.EMPTY;
        }
    }

    private static boolean canRunClick(Announcement a, ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (a.clickPermission != null && !a.clickPermission.isBlank()) {
            return PermissionChecker.check(player.createCommandSourceStack(), a.clickPermission);
        }
        return com.shenqianye.samsara.permission.PermissionChecker
                .getPlayerLevel(player.createCommandSourceStack())
                .meets(com.shenqianye.samsara.permission.PermissionLevel.OP);
    }

    private static Style hoverStyle(Announcement a, ServerPlayer player, MinecraftServer server) {
        if (a.hoverText == null || a.hoverText.isBlank()) {
            return Style.EMPTY;
        }
        MutableComponent text = component(a.hoverText, new Announcement(), player, server);
        return Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(text));
    }

    private static String substitute(String text, ServerPlayer player, MinecraftServer server) {
        String raw = text == null ? "" : text;
        String name = player == null ? "玩家" : player.getScoreboardName();
        String display = player == null ? name : player.getDisplayName().getString();
        String world = player == null ? "server" : player.level().dimension().identifier().toString();
        String online = server == null ? "0" : String.valueOf(server.getPlayerList().getPlayerCount());
        return raw.replace("{player}", name)
                .replace("{display}", display)
                .replace("{world}", world)
                .replace("{dimension}", world)
                .replace("{online}", online)
                .replace("{time}", LocalTime.now().format(TIME))
                .replace("{date}", LocalDate.now().format(DATE))
                .replace("{prefix}", "[公告]");
    }

}
