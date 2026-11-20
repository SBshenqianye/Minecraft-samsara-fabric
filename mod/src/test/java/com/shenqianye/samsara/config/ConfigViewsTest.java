package com.shenqianye.samsara.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 配置视图 record 的 JSON 契约：字段名与旧 Map 输出一致，缺省字段不落键。 */
class ConfigViewsTest {

    private static final Gson GSON = new Gson();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Object value) {
        return GSON.fromJson(GSON.toJson(value), Map.class);
    }

    @Test
    void entryRenamesDefaultAndOmitsSensitiveFlags() {
        ConfigViews.Entry entry = new ConfigViews.Entry("view.aim.enabled", "boolean", "说明",
                "回看", false, null, null, null, List.of(), true, false, null, null);
        Map<String, Object> out = json(entry);
        assertFalse(out.containsKey("defaultValue"));
        assertFalse(out.containsKey("sensitive"));
        assertFalse(out.containsKey("configured"));
        assertEquals(true, out.get("default"));
        assertEquals(List.of("path", "type", "description", "group", "restart", "choices",
                "default", "value"), List.copyOf(new LinkedHashSet<>(out.keySet())));
    }

    @Test
    void sensitiveEntryKeepsFlags() {
        Map<String, Object> out = json(new ConfigViews.Entry("token", "string", "", "", false,
                "", null, null, List.of(), "", "", true, true));
        assertEquals(Boolean.TRUE, out.get("sensitive"));
        assertEquals(Boolean.TRUE, out.get("configured"));
    }

    @Test
    void modelKeepsFilesAndWarnings() {
        ConfigViews.Model model = new ConfigViews.Model(
                List.of(new ConfigViews.File("commands", "commands.yml", "/x/commands.yml", List.of())),
                List.of("warn"));
        Map<String, Object> out = json(model);
        assertEquals(Set.of("files", "warnings"), out.keySet());
    }

    @Test
    void previewSaveKeepsLegacyFields() {
        ConfigViews.Save save = new ConfigViews.Save(true, null, null, false, Map.of(), "samsara:commands",
                "rev-1", 2, List.of(new ConfigViews.Change("a.b", 1, 2)), "- a.b: 1\n+ a.b: 2\n",
                null, null);
        Map<String, Object> out = json(save);
        assertEquals(List.of("ok", "requiresConfirmation", "fields", "file", "revision", "changed",
                        "changes", "diff"),
                List.copyOf(new LinkedHashSet<>(out.keySet())));
        assertEquals(List.of("path", "before", "after"),
                List.copyOf(((Map<String, Object>) ((List<?>) out.get("changes")).get(0)).keySet()));
    }

    @Test
    void errorSaveOnlyHasOkAndError() {
        Map<String, Object> out = json(new ConfigViews.Save(false, "boom", null, null, null, null,
                null, null, null, null, null, null));
        assertEquals(Set.of("ok", "error"), out.keySet());
    }

    @Test
    void curveKeepsEstimateFields() {
        ConfigViews.Curve curve = new ConfigViews.Curve(true, 6L, 6.0, 100L, 20L, 3L, 1L, 100.0,
                1000L, 6000L, 5000L, 400L, false, false, "",
                List.of(new ConfigViews.CurveTier(7, 0.5, 100L, 700L, true)));
        Map<String, Object> out = json(curve);
        assertEquals(Set.of("ok", "bytesPerSample", "actualBytesPerSample", "actualTotalBytes",
                "actualTotalSamples", "actualFileCount", "actualPlayerCount",
                "actualBytesPerPlayer", "freshSamplesPerDay", "freshBytesPerDay", "steadyBytes",
                "growthPerDay", "unbounded", "keepAll", "warning", "tiers"), out.keySet());
        assertEquals(Set.of("ageDays", "keepRatio", "bytesPerDay", "cumulativeBytes", "older"),
                ((Map<String, Object>) ((List<?>) out.get("tiers")).get(0)).keySet());
    }
}
