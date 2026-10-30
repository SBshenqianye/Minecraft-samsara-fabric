package com.shenqianye.samsara.web;

import com.shenqianye.samsara.config.CommandConfig;
import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;
import com.shenqianye.samsara.config.YamlStore;
import com.shenqianye.samsara.output.OutputButton;
import com.shenqianye.samsara.output.OutputCatalog;
import com.shenqianye.samsara.output.OutputPreviewFragment;
import com.shenqianye.samsara.output.OutputRenderer;
import com.shenqianye.samsara.output.OutputTemplate;
import com.shenqianye.samsara.output.OutputVariable;
import com.shenqianye.samsara.output.TemplateCodec;
import com.shenqianye.samsara.output.TemplatePart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 生产环境输出格式 Web 服务。 */
public final class WebOutputService implements OutputWebService {
    @Override
    public Map<String, Object> model() {
        ConfigManager config = ConfigManager.getInstance();
        Map<String, Object> values = config.snapshot(ConfigSchema.OUTPUT);
        List<Map<String, Object>> messages = new ArrayList<>();
        List<String> warnings = new ArrayList<>(config.warnings());
        for (OutputTemplate template : OutputCatalog.all()) {
            List<TemplatePart> parts = readParts(template, values.get(template.key()), warnings);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("key", template.key());
            message.put("group", template.group());
            message.put("description", template.description());
            message.put("variables", variables(template));
            message.put("buttons", buttons(template));
            message.put("parts", TemplateCodec.toConfig(parts));
            message.put("defaultParts", TemplateCodec.toConfig(template.defaultParts()));
            String disabled = disabledReason(template.key());
            message.put("editable", disabled == null);
            message.put("disabledReason", disabled == null ? "" : disabled);
            messages.add(message);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("path", config.file(ConfigSchema.OUTPUT).toAbsolutePath().normalize().toString());
        out.put("messages", messages);
        out.put("warnings", warnings);
        return out;
    }

    @Override
    public Map<String, Object> save(Map<String, Object> body) {
        String key = string(body.get("key"));
        OutputTemplate template = OutputCatalog.template(key);
        if (template == null) {
            return fail("未知输出消息", Map.of("key", "未知输出消息：" + key));
        }
        String disabled = disabledReason(key);
        if (disabled != null) {
            return fail("该消息当前不可编辑", Map.of("key", disabled));
        }
        List<TemplatePart> parts;
        try {
            parts = TemplateCodec.parse(body.get("parts"));
        } catch (TemplateCodec.FormatException e) {
            return fail("输出模板格式不正确", Map.of(e.field(), e.getMessage()));
        }
        Map<String, String> errors = TemplateCodec.validate(template, parts);
        if (!errors.isEmpty()) {
            return fail("输出模板校验失败", errors);
        }
        YamlStore.SaveResult result = ConfigManager.getInstance().save(ConfigSchema.OUTPUT,
                Map.of(key, TemplateCodec.toConfig(parts)));
        if (!result.ok()) {
            return fail("保存失败", result.errors());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "已保存并重载 " + key);
        out.put("output", model());
        return out;
    }

    @Override
    public Map<String, Object> preview(Map<String, Object> body) {
        String key = string(body.get("key"));
        OutputTemplate template = OutputCatalog.template(key);
        if (template == null) {
            return fail("未知输出消息", Map.of("key", "未知输出消息：" + key));
        }
        List<TemplatePart> parts;
        try {
            parts = TemplateCodec.parse(body.get("parts"));
        } catch (TemplateCodec.FormatException e) {
            return fail("输出模板格式不正确", Map.of(e.field(), e.getMessage()));
        }
        Map<String, String> errors = TemplateCodec.validate(template, parts);
        if (!errors.isEmpty()) {
            return fail("输出模板校验失败", errors);
        }
        List<Map<String, Object>> fragments = new ArrayList<>();
        List<OutputPreviewFragment> rendered = OutputRenderer.preview(key, parts);
        for (OutputPreviewFragment fragment : rendered) {
            fragments.add(fragment(fragment));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("key", key);
        out.put("fragments", fragments);
        out.put("plain", OutputRenderer.plainText(rendered));
        return out;
    }

    @Override
    public Map<String, Object> reload() {
        ConfigManager.getInstance().reload(ConfigSchema.OUTPUT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "输出模板已重载");
        out.put("output", model());
        return out;
    }

    private static List<TemplatePart> readParts(OutputTemplate template, Object raw,
                                                List<String> warnings) {
        if (raw == null) {
            return template.defaultParts();
        }
        try {
            List<TemplatePart> parts = TemplateCodec.parse(raw);
            Map<String, String> errors = TemplateCodec.validate(template, parts);
            if (errors.isEmpty()) {
                return parts;
            }
            warnings.add(template.key() + " 模板非法（" + errors.values().iterator().next()
                    + "），已回退默认");
        } catch (TemplateCodec.FormatException e) {
            warnings.add(template.key() + " 模板非法（" + e.getMessage() + "），已回退默认");
        }
        return template.defaultParts();
    }

    private static List<Map<String, Object>> variables(OutputTemplate template) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OutputVariable variable : template.variables()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", variable.key());
            item.put("label", variable.label());
            item.put("sample", variable.sample());
            item.put("required", variable.required());
            item.put("type", variable.type().id());
            out.add(item);
        }
        return out;
    }

    private static List<Map<String, Object>> buttons(OutputTemplate template) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OutputButton button : template.buttons()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", button.key());
            item.put("label", button.label());
            item.put("command", button.command());
            item.put("hover", button.hover());
            item.put("style", button.style());
            item.put("required", button.required());
            out.add(item);
        }
        return out;
    }

    private static Map<String, Object> fragment(OutputPreviewFragment fragment) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", fragment.kind());
        item.put("text", fragment.text());
        item.put("color", fragment.color());
        item.put("bold", fragment.bold());
        item.put("italic", fragment.italic());
        item.put("underline", fragment.underline());
        item.put("strikethrough", fragment.strikethrough());
        item.put("clickable", fragment.clickable());
        item.put("clickCommand", fragment.clickCommand());
        item.put("clickMode", fragment.clickMode());
        item.put("hover", fragment.hover());
        return item;
    }

    private static String disabledReason(String key) {
        if (!key.startsWith("where.")) {
            return null;
        }
        CommandConfig config = CommandConfig.getInstance();
        boolean any = config.whereShowDimension() || config.whereShowCoordinates()
                || config.whereShowChunk() || config.whereShowFacing();
        if (!any) {
            return "位置输出已全部关闭";
        }
        boolean all = config.whereShowDimension() && config.whereShowCoordinates()
                && config.whereShowChunk() && config.whereShowFacing();
        if (!all) {
            return "where 的显示字段未全部开启，当前使用内置输出；全部开启后可编辑模板";
        }
        return null;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Map<String, Object> fail(String error, Map<String, String> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", error);
        out.put("fields", fields);
        return out;
    }
}
