package com.shenqianye.samsara.permission;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.shenqianye.samsara.command.CommandAliases;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 启动时从 Brigadier 命令树抓取全部 literal 路径，供权限树展示与执行期校验。 */
public final class PermissionCommandPaths {
    private static volatile Set<String> paths = Set.of();

    private PermissionCommandPaths() {
    }

    public static void capture(MinecraftServer server) {
        if (server == null) {
            paths = Set.of();
            return;
        }
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        Set<String> result = new TreeSet<>();
        for (String root : CommandAliases.roots()) {
            CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(root);
            if (node == null) {
                continue;
            }
            if ("samsara".equals(root)) {
                for (CommandNode<CommandSourceStack> child : node.getChildren()) {
                    captureNode(child, "", result);
                }
            } else {
                captureNode(node, "", result);
            }
        }
        paths = Set.copyOf(result);
    }

    public static Set<String> paths() {
        return paths;
    }

    private static void captureNode(CommandNode<CommandSourceStack> node, String prefix,
                                    Set<String> out) {
        if (!(node instanceof LiteralCommandNode<CommandSourceStack> literal)) {
            return;
        }
        String path = canonicalPath(prefix.isBlank()
                ? literal.getLiteral()
                : prefix + " " + literal.getLiteral());
        out.add(path);
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            captureNode(child, path, out);
        }
    }

    /** 从解析结果拼出与权限配置一致的相对路径。 */
    public static String pathOf(List<String> literals) {
        List<String> tokens = new ArrayList<>(literals);
        if (!tokens.isEmpty() && "samsara".equals(tokens.get(0))) {
            tokens.remove(0);
        }
        return canonicalPath(String.join(" ", tokens));
    }

    /**
     * 别名根节点归一化到主命令路径，保证补全期与执行期使用同一个权限 key。
     *
     * <p>{@code /tpyes} → {@code tpaccept}、{@code /tpno} → {@code tpdeny}；
     * 其他短别名（如 {@code /season}）去掉根斜杠后本来就和 {@code /samsara season}
     * 使用同一路径，不需要额外映射。
     */
    public static String canonicalPath(String path) {
        if (path == null) {
            return "";
        }
        String value = path.trim();
        return switch (value) {
            case "tpyes" -> "tpaccept";
            case "tpno" -> "tpdeny";
            default -> value;
        };
    }
}
