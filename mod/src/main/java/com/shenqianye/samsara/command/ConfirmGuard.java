package com.shenqianye.samsara.command;

import java.util.HashMap;
import java.util.Map;

/**
 * 破坏性操作的二次确认守卫（实现《设计规范》〈破坏性操作：二次确认〉）。
 * <p>
 * 用法：在命令的 executes 里调用 {@link #confirm(String)}——
 * <pre>{@code
 * if (!ConfirmGuard.confirm("season.start")) {
 *     return "⚠ 危险操作确认：……\n再次输入同一命令（" + ConfirmGuard.ttlSeconds() + " 秒内）才会真正执行。";
 * }
 * // 走到这里说明是"确认"的那一次，可以真正执行
 * }</pre>
 * 首次调用返回 {@code false} 并进入待确认状态；在有效期内再次调用返回 {@code true} 并解除待确认状态。
 * <p>
 * <b>为什么带有效期（TTL）</b>：早期实现在 {@code DebugCommand} 里用"永久 boolean 标记"，
 * 一旦置位就一直有效——管理员误触一次之后，几十次命令之后的又一次同名输入会**直接执行**，
 * 反而放大了误操作风险。本类给待确认状态加 30 秒有效期：超时即自动失效，需重新确认。
 * <p>
 * <b>为什么按 key 隔离</b>：不同破坏性操作各自独立待确认，互不影响
 * （确认 {@code season start} 不会顺带把 {@code rewind purge} 也变成"已确认"）。
 * <p>
 * 线程安全：命令在主线程执行，仍对状态表加锁以防未来从其它线程调用。
 */
public final class ConfirmGuard {

    /** 待确认状态的有效期（毫秒）：超时自动失效，需重新确认 */
    private static final long TTL_MS = 30_000;

    /** key → 进入待确认状态的时刻（毫秒） */
    private static final Map<String, Long> ARMED_AT = new HashMap<>();

    private ConfirmGuard() {
    }

    /**
     * 询问确认状态。
     *
     * @param key 操作标识（如 {@code "season.start"}），不同操作各自独立
     * @return {@code true} = 本次是"确认执行"那一次；{@code false} = 首次（或已超时）调用，已进入待确认状态
     */
    public static synchronized boolean confirm(String key) {
        long now = System.currentTimeMillis();
        Long armedAt = ARMED_AT.get(key);
        if (armedAt != null && now - armedAt <= TTL_MS) {
            ARMED_AT.remove(key);
            return true;
        }
        ARMED_AT.put(key, now);
        return false;
    }

    /** 主动解除待确认状态（操作被拒绝/条件不满足时调用，避免留下半武装状态） */
    public static synchronized void disarm(String key) {
        ARMED_AT.remove(key);
    }

    /** 该操作当前是否处于待确认状态（且未超时） */
    public static synchronized boolean isArmed(String key) {
        Long armedAt = ARMED_AT.get(key);
        return armedAt != null && System.currentTimeMillis() - armedAt <= TTL_MS;
    }

    /** 待确认状态的有效期秒数（用于拼提示文案，避免文案与实现脱节） */
    public static long ttlSeconds() {
        return TTL_MS / 1000;
    }
}
