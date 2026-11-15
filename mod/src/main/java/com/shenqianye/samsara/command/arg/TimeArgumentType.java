package com.shenqianye.samsara.command.arg;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.network.chat.Component;

/**
 * 时间参数：允许 {@code 23:28}、{@code 2026-10-28T23:28}、epoch 毫秒，直到空白为止。
 * <p>
 * {@code StringArgumentType.word()} 只允许字母数字和 {@code _-.+}，
 * {@code string()} 的非引号分支同样拒绝 {@code :}——两者都会把 {@code 23:28}
 * 判成 “trailing data”。这里按“非空白即字符”读取，不做二次格式化。
 */
public final class TimeArgumentType implements ArgumentType<String> {
    private static final SimpleCommandExceptionType EXPECTED =
            new SimpleCommandExceptionType(Component.literal("需要一个时间，例如 23:28、2026-10-28T23:28 或 epoch 毫秒"));
    private static final TimeArgumentType INSTANCE = new TimeArgumentType();

    private TimeArgumentType() {
    }

    public static TimeArgumentType time() {
        return INSTANCE;
    }

    public static String getTime(CommandContext<?> ctx, String name) {
        return ctx.getArgument(name, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        if (reader.getCursor() == start) {
            throw EXPECTED.createWithContext(reader);
        }
        return reader.getString().substring(start, reader.getCursor());
    }
}
