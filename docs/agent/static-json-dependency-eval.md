# 最后两个静态 JSON 依赖评估

> 生成时间：2026-11-14  
> 范围：`mod/src/main/resources/web/` 下剩余的 `item-lang.json`、`openapi.json`

## 结论

| 文件 | 当前用途 | 是否 HTTP 公开 | 处理 |
|---|---|---|---|
| `item-lang.json` | `ItemLang`、`ItemValueService` 通过 classpath 读取物品中文名 | 否 | **已移出 web**：`assets/samsara/lang/item-lang.json` |
| `openapi.json` | `/openapi.json` Agent API 契约；文档与 skill 均引用 | 是 | **保留**：它是公开 API 契约，不是旧页面残留 |

batch 5 后，`mod/src/main/resources/web/` 只剩 `openapi.json` 一个静态 JSON；旧页面壳、旧脚本和 `style.css` 已全部删除。

## item-lang.json

### 现状

- 大小：395,426 bytes；2765 个物品条目。
- 读取方：
  - `com.shenqianye.samsara.item.ItemLang`
  - `com.shenqianye.samsara.lottery.ItemValueService`
- 读取方式：`getResourceAsStream("/assets/samsara/lang/item-lang.json")`
- 不经过 `WebSecurity.staticResource`，也不在 `/web` 静态目录暴露。

### 为什么可以移出 web

它只是服务端展示用的中文名查询表，不参与 HTTP 路由，也没有前端直接请求 `/item-lang.json`。把它放在 `web/` 下会让“Web 静态资源”目录混入非 Web 数据。

### 已完成

- `git mv mod/src/main/resources/web/item-lang.json mod/src/main/resources/assets/samsara/lang/item-lang.json`
- 更新两个 Java 读取路径。
- 新增 `ItemLangTest`：验证资源可加载、`minecraft:diamond` 返回“钻石”、空 ID 和未知 ID 返回空串。
- `gradle test`：541 tests / 0 failures。

### 后续可选

如果以后要进一步减小 jar，可以：

1. 只保留 `zh` 列，移除 `initials` / `py` / `syllables`（需确认没有其他读取方）。
2. 在构建时由物品注册表生成，而不是提交静态文件。
3. 改为按需加载 + 缓存，而不是启动时全量解析。

当前不做，因为 395 KB 对 jar 体积影响很小，且 2765 条全量加载在启动期一次性完成，运行时无 IO。

## openapi.json

### 现状

- 大小：8,563 bytes。
- 公开路径：`GET /openapi.json` → `/web/openapi.json`。
- 被以下位置引用：
  - `docs/Agent API.md`
  - `.opencode/skills/samsara-server/SKILL.md`
  - `docs/agent/prompts/system.md`
  - `WebSecurityTest`
- 作用：外部 Agent 发现接口、scope、请求体与幂等约定。

### 为什么保留

删除它会直接破坏已文档化的 Agent API 发现入口，属于破坏性变更；而且它不是“旧页面残留”，而是当前 API 的契约文件。

### 后续可选

若目标是“Web 目录不放大体积 JSON”，可以把 `openapi.json` 移到 `docs/`，再由 Java 构建时复制到 classpath；但这只是移动文件，不减少依赖。

更彻底的做法是从路由注册表生成 OpenAPI，避免手写文件与实现漂移；这需要单独任务，不建议在本轮顺手做。

## 验收

```text
gradle test        541 tests / 0 failures
npm run check      69 tests / 0 failures
web E2E            281 / 281 通过
batch5 section     8 / 8 通过
```

`mod/src/main/resources/web/` 最终内容：

```text
openapi.json
```
