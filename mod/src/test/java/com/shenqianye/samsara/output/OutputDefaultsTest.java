package com.shenqianye.samsara.output;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 默认模板快照：必须逐字复现迁移前的输出。 */
class OutputDefaultsTest {
    @Test
    void whereResultMatchesLegacyOutput() {
        assertEquals("[周目管理] ▸ 主世界（minecraft:overworld）\n"
                        + "坐标 55, 48, 4\n"
                        + "区块 3, 0 · 朝向 北\n"
                        + "[广播位置]",
                plain("where.result", OutputCatalog.sampleValues("where.result")));
    }

    @Test
    void whereBroadcastMatchesLegacyOutput() {
        assertEquals("[周目管理] ▸ Alice 的位置\n"
                        + "主世界（minecraft:overworld）\n"
                        + "坐标 55, 48, 4\n"
                        + "区块 3, 0 · 朝向 北",
                plain("where.broadcast", OutputCatalog.sampleValues("where.broadcast")));
    }

    @Test
    void additionalPlayerMessagesMatchLegacyOutput() {
        assertEquals("[周目管理] ▸ 周目管理（服务端周目管理 (Samsara)） · 版本 0.21.0"
                        + " · MC 26.3 / Fabric —— 空间/时间/逆熵",
                plain("version", OutputCatalog.sampleValues("version")));
        assertEquals("[周目管理] ▸ 周目管理 samsara · 分组出生（设计见 docs/周目系统设计文档.md）"
                        + "\n主世界种子: 1077061463 · 出生点: 55, 91, 3",
                plain("season.info", OutputCatalog.sampleValues("season.info")));
        assertEquals("[周目管理] ▸ 边界持续显示已开启 ✓（进入管理区域自动显示）",
                plain("claim.boundary", OutputCatalog.sampleValues("claim.boundary")));
        assertEquals("[周目管理] ▸ 广播冷却中，请 24 秒后再试",
                plain("where.cooldown", OutputCatalog.sampleValues("where.cooldown")));
    }

    @Test
    void identityStatusMatchesLegacyOutput() {
        Map<String, Object> values = new LinkedHashMap<>(OutputCatalog.sampleValues("identity.status"));
        values.put("noteLine", "");
        values.put("lastWriterLine", "");
        values.put("blockedLine", "");
        assertEquals("[周目管理] ▸ 身份组「Bot」\n"
                        + "data UUID: 00000000-0000-0000-0000-000000000000\n"
                        + "premium: （未设置）\n"
                        + "offline: （未设置）",
                plain("identity.status", values));
    }

    @Test
    void identityEdgeBranchesMatchLegacyOutput() {
        assertEquals("[周目管理] ▸ 身份验证已关闭（identity.enabled=false）。身份组只做账号映射，不提供注册/登录。",
                plain("identity.status.disabled", Map.of()));
        assertEquals("[周目管理] ▸ 身份组配置非法，身份解析已停用：\n  · groups[0].data：UUID 格式不正确",
                plain("identity.status.invalid",
                        OutputCatalog.sampleValues("identity.status.invalid")));
        assertEquals("[周目管理] ▸ 未加入身份组 · data = 自身 UUID 00000000-0000-0000-0000-000000000000",
                plain("identity.status.ungrouped",
                        OutputCatalog.sampleValues("identity.status.ungrouped")));
    }

    @Test
    void claimInfoMatchesLegacyOutput() {
        assertEquals("[周目管理] ▸ 区块 (3,0) minecraft:overworld ✓ 受保护\n"
                        + "  旗帜朝向: 北 · 主人: Alice · 图案: base(1区块)\n"
                        + "[查看当前区块]",
                plain("claim.info", OutputCatalog.sampleValues("claim.info")));
    }

    @Test
    void seasonItemAndHeaderMatchLegacyOutput() {
        assertEquals("#1「初始」 ACTIVE · same-world · 始于 2026-10-30 12:00:00"
                        + " · 止于 2026-11-01 12:00:00 · 分组 3 组",
                plain("season.status.item", OutputCatalog.sampleValues("season.status.item")));
        assertEquals("[周目管理] ▸ \n--- 第 1/2 页，共 9 条 ---"
                        + "\n当前：#1「初始」 ACTIVE"
                        + "\n世界栈：world"
                        + "\n时间倒流进行中（50%）"
                        + "\n[刷新]",
                plain("season.status.header", OutputCatalog.sampleValues("season.status.header")));
    }

    private static String plain(String key, Map<String, Object> values) {
        OutputTemplate template = OutputCatalog.template(key);
        List<OutputPreviewFragment> fragments = OutputRenderer.renderRaw(template,
                template.defaultParts(), values, OutputRenderer.ButtonPolicy.all());
        return OutputRenderer.plainText(fragments);
    }
}
