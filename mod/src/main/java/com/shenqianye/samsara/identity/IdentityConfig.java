package com.shenqianye.samsara.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v0.20 身份组配置模型（纯数据，不依赖 Minecraft/Fabric）。
 *
 * <p>文件：{@code config/samsara/identity.yml}，服务器级，不随世界栈切换。
 * 结构见《设计文档》〈身份组（身份映射，v0.20 重新设计）〉：
 *
 * <pre>
 * identity:
 *   enabled: false
 *   syncPlayerData: true
 * pets:
 *   maxOwners: 32
 * groups:
 *   - name: Steve
 *     data: &lt;uuid&gt;
 *     premium: &lt;uuid|null&gt;
 *     offline: &lt;uuid|null&gt;
 *     note: ...
 * </pre>
 *
 * <p>本类只描述数据；校验在 {@link IdentityValidator}，读写由
 * {@link IdentityConfigStore} 负责，便于单测与 Web/命令共用。
 */
public final class IdentityConfig {
    /** 单只宠物默认最多主人数量。 */
    public static final int DEFAULT_MAX_OWNERS = 32;

    public boolean enabled = false;
    public boolean syncPlayerData = true;
    public int maxOwners = DEFAULT_MAX_OWNERS;
    public List<Group> groups = new ArrayList<>();

    /**
     * data UUID 级别的管理员元数据（名称/备注）。
     *
     * <p>只用于 Web 端辨识，例如把某个 data UUID 标记成「小明 2026-01 的存档」，
     * 避免账号 UUID 与 data UUID 混用、归还时拿错存档。不参与身份解析。
     */
    public Map<String, Label> dataLabels = new LinkedHashMap<>();

    /** data UUID 的可编辑元数据。 */
    public static final class Label {
        public String name = "";
        public String note = "";

        public Label() {
        }

        public Label(String name, String note) {
            this.name = name == null ? "" : name;
            this.note = note == null ? "" : note;
        }

        public Label copy() {
            return new Label(name, note);
        }
    }

    /** 一个身份组：显示名 + 数据 UUID + 最多一个正版账号/离线账号入口。 */
    public static final class Group {
        public String name = "";
        public String data = "";
        public String premium;
        public String offline;
        public String note = "";

        public Group() {
        }

        public Group(String name, UUID data, UUID premium, UUID offline, String note) {
            this.name = name == null ? "" : name;
            this.data = data == null ? "" : data.toString();
            this.premium = premium == null ? null : premium.toString();
            this.offline = offline == null ? null : offline.toString();
            this.note = note == null ? "" : note;
        }

        public Group copy() {
            Group copy = new Group();
            copy.name = name;
            copy.data = data;
            copy.premium = premium;
            copy.offline = offline;
            copy.note = note;
            return copy;
        }

        public UUID dataUuid() {
            return parse(data);
        }

        public UUID premiumUuid() {
            return parse(premium);
        }

        public UUID offlineUuid() {
            return parse(offline);
        }

        /** 组内所有实际账号入口（不含单独的 data UUID）。 */
        public List<UUID> accountUuids() {
            List<UUID> out = new ArrayList<>(2);
            UUID value = premiumUuid();
            if (value != null) {
                out.add(value);
            }
            value = offlineUuid();
            if (value != null) {
                out.add(value);
            }
            return out;
        }

        /** 该实际 UUID 是否属于本组。 */
        public boolean contains(UUID uuid) {
            if (uuid == null) {
                return false;
            }
            return uuid.equals(premiumUuid()) || uuid.equals(offlineUuid());
        }
    }

    public IdentityConfig copy() {
        IdentityConfig copy = new IdentityConfig();
        copy.enabled = enabled;
        copy.syncPlayerData = syncPlayerData;
        copy.maxOwners = maxOwners;
        for (Map.Entry<String, Label> entry : dataLabels.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.dataLabels.put(entry.getKey(), entry.getValue().copy());
            }
        }
        for (Group group : groups) {
            if (group != null) {
                copy.groups.add(group.copy());
            }
        }
        return copy;
    }

    public static UUID parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
