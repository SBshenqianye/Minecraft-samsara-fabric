package com.shenqianye.samsara.audit;

/**
 * 审计写入的领域侧入口。
 *
 * <p>业务模块（container 等）只依赖这里；真正的实现（{@code web/WebAuditService}）在
 * mod 初始化时通过 {@link #install(Sink)} 装进来，避免领域代码反向依赖 web 层。
 *
 * <p>没有安装实现时退化为 no-op：审计失败绝不能阻塞业务写入。
 */
public final class Audit {
    /** 审计实现（由外层装配）。 */
    public interface Sink {
        void captureInventory(String action, String actor, String detail, String snapshotJson);
    }

    private static volatile Sink sink;

    private Audit() {
    }

    /** 安装实现；重复安装以最后一次为准。 */
    public static void install(Sink implementation) {
        sink = implementation;
    }

    /** 背包编辑前的快照；未安装实现时静默跳过。 */
    public static void captureInventory(String action, String actor, String detail,
                                        String snapshotJson) {
        Sink current = sink;
        if (current == null) {
            return;
        }
        current.captureInventory(action, actor, detail, snapshotJson);
    }
}
