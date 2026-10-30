package com.shenqianye.samsara.command.ui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

/**
 * 补全翻页按钮的点击协议（{@code ClickEvent.Custom}）。
 *
 * <p>不注册隐藏命令：隐藏命令会出现在 Tab 补全与帮助列表里，污染命名空间；
 * 自定义点击动作只由本模组的页面按钮发出，服务端收到后重新算候选并重发一页。
 *
 * <p>payload 是 NBT：
 * <pre>
 * input  : 原始输入（不含前导 /）
 * page   : 目标页（1 起，服务端仍会钳制）
 * nonce  : 可选的客户端会话标记（当前只记录，不参与鉴权）
 * </pre>
 */
public final class CompletionAction {
    /** 与 {@code ClickEvent.Custom} / {@code ServerboundCustomClickActionPacket} 共用的 id。 */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("samsara", "completion_page");
    public static final String KEY_INPUT = "input";
    public static final String KEY_PAGE = "page";
    public static final String KEY_NONCE = "nonce";

    private CompletionAction() {
    }

    /** 构造翻页 payload；输入只保留一行，避免 NBT/聊天注入。 */
    public static CompoundTag payload(String input, int page, String nonce) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_INPUT, sanitize(input));
        tag.putInt(KEY_PAGE, page);
        if (nonce != null && !nonce.isBlank()) {
            tag.putString(KEY_NONCE, sanitize(nonce));
        }
        return tag;
    }

    /** 解析请求；缺 input、类型不对时返回 null（调用方静默忽略）。 */
    public static Request parse(Tag tag) {
        if (!(tag instanceof CompoundTag compound)) {
            return null;
        }
        String input = compound.getStringOr(KEY_INPUT, "");
        if (input.isBlank()) {
            return null;
        }
        int page = compound.getIntOr(KEY_PAGE, 1);
        String nonce = compound.getStringOr(KEY_NONCE, "");
        return new Request(input, page, nonce);
    }

    /** 翻页请求。 */
    public record Request(String input, int page, String nonce) {
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\0') {
                continue;
            }
            out.append(c);
        }
        return out.toString().strip();
    }
}
