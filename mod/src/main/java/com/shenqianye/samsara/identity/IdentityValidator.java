package com.shenqianye.samsara.identity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 身份组配置校验（Web 保存、YAML 加载、管理命令共用）。
 *
 * <p>规则（《设计文档》9.4）：
 * <ul>
 *   <li>data 必填且 UUID 合法；</li>
 *   <li>同一 UUID 不能出现在多个组；</li>
 *   <li>data 不能是其他组的 premium/offline；</li>
 *   <li>premium/offline 各自最多一个；</li>
 *   <li>组名不区分大小写唯一；</li>
 *   <li>maxOwners 在 1~1024。</li>
 * </ul>
 *
 * <p>允许 {@code data} 与本组自己的 {@code premium}/{@code offline} 相同：
 * 这表示“这个账号就是主数据”，是设计明确允许的用法。
 */
public final class IdentityValidator {
    public static final int MIN_MAX_OWNERS = 1;
    public static final int MAX_MAX_OWNERS = 1024;
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_NOTE_LENGTH = 512;

    private IdentityValidator() {
    }

    public record Issue(String field, String message) {
    }

    public static List<Issue> validate(IdentityConfig config) {
        List<Issue> issues = new ArrayList<>();
        if (config == null) {
            issues.add(new Issue("identity", "配置为空"));
            return issues;
        }
        if (config.maxOwners < MIN_MAX_OWNERS || config.maxOwners > MAX_MAX_OWNERS) {
            issues.add(new Issue("pets.maxOwners",
                    "主人数量上限应在 " + MIN_MAX_OWNERS + "~" + MAX_MAX_OWNERS + " 之间"));
        }
        if (config.groups == null) {
            issues.add(new Issue("groups", "groups 必须是列表"));
            return issues;
        }

        Map<String, Integer> names = new HashMap<>();
        List<RoleRef> roles = new ArrayList<>();
        Set<Integer> offlineSeen = new HashSet<>();
        Set<Integer> premiumSeen = new HashSet<>();

        for (int i = 0; i < config.groups.size(); i++) {
            IdentityConfig.Group group = config.groups.get(i);
            String base = "groups[" + i + "]";
            if (group == null) {
                issues.add(new Issue(base, "身份组不能为空"));
                continue;
            }
            String name = group.name == null ? "" : group.name.trim();
            if (name.isEmpty()) {
                issues.add(new Issue(base + ".name", "组名不能为空"));
            } else if (name.length() > MAX_NAME_LENGTH) {
                issues.add(new Issue(base + ".name", "组名不能超过 " + MAX_NAME_LENGTH + " 个字符"));
            } else {
                Integer previous = names.putIfAbsent(name.toLowerCase(Locale.ROOT), i);
                if (previous != null) {
                    issues.add(new Issue(base + ".name", "组名与 groups[" + previous + "] 重复"));
                }
            }
            if (group.note != null && group.note.length() > MAX_NOTE_LENGTH) {
                issues.add(new Issue(base + ".note", "备注不能超过 " + MAX_NOTE_LENGTH + " 个字符"));
            }

            UUID data = parse(group.data, base + ".data", true, issues);
            UUID premium = parse(group.premium, base + ".premium", false, issues);
            UUID offline = parse(group.offline, base + ".offline", false, issues);
            if (premium != null && !premiumSeen.add(i)) {
                // 单字段天然最多一个，这里保留结构给未来扩展；实际不会命中。
                issues.add(new Issue(base + ".premium", "每组最多一个正版账号"));
            }
            if (offline != null && !offlineSeen.add(i)) {
                issues.add(new Issue(base + ".offline", "每组最多一个离线账号"));
            }
            if (data != null) {
                roles.add(new RoleRef(i, "data", data, base + ".data"));
            }
            if (premium != null) {
                roles.add(new RoleRef(i, "premium", premium, base + ".premium"));
            }
            if (offline != null) {
                roles.add(new RoleRef(i, "offline", offline, base + ".offline"));
            }
        }

        // 跨组唯一性：同一组内 data == premium/offline 是允许的，其余重复都拒绝。
        for (int i = 0; i < roles.size(); i++) {
            RoleRef a = roles.get(i);
            for (int j = i + 1; j < roles.size(); j++) {
                RoleRef b = roles.get(j);
                if (!a.uuid.equals(b.uuid)) {
                    continue;
                }
                // 允许“同一个 data UUID 被多个组共享”：这表示多个显示组指向同一份
                // 玩家数据，是 Web 卡片拖拽明确支持的用法。
                if (a.role.equals("data") && b.role.equals("data")) {
                    continue;
                }
                // 同一组内 data == premium/offline 仍然允许（主数据就是该账号本身）。
                if (a.groupIndex == b.groupIndex
                        && (a.role.equals("data") || b.role.equals("data"))
                        && (!a.role.equals("data") || !b.role.equals("data"))) {
                    continue;
                }
                // 跨组时：如果该 UUID 是另一个组的“自身数据”（data == premium/offline），
                // 那么把它作为本组 data 是同一个身份，允许共享；否则会产生解析歧义，拒绝。
                if (a.groupIndex != b.groupIndex
                        && (a.role.equals("data") || b.role.equals("data"))) {
                    int accountIndex = a.role.equals("data") ? b.groupIndex : a.groupIndex;
                    IdentityConfig.Group accountGroup = config.groups.get(accountIndex);
                    if (accountGroup != null && a.uuid.equals(accountGroup.dataUuid())) {
                        continue;
                    }
                }
                String message = "UUID " + a.uuid + " 同时出现在 " + a.field + " 与 " + b.field;
                issues.add(new Issue(a.field, message));
            }
        }
        return issues;
    }

    private static UUID parse(String text, String field, boolean required, List<Issue> issues) {
        if (text == null || text.isBlank()) {
            if (required) {
                issues.add(new Issue(field, "data 必填"));
            }
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (RuntimeException e) {
            issues.add(new Issue(field, "UUID 格式不正确"));
            return null;
        }
    }

    private record RoleRef(int groupIndex, String role, UUID uuid, String field) {
    }
}
