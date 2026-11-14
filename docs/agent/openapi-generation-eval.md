# openapi.json 生成化评估

> 日期：2026-11-14  
> 当前文件：`mod/src/main/resources/web/openapi.json`（8.5 KB）

## 当前状态

- 公开路径：`GET /openapi.json` → `/web/openapi.json`
- 引用位置：
  - `docs/Agent API.md`
  - `.opencode/skills/samsara-server/SKILL.md`
  - `docs/agent/prompts/system.md`
  - `WebSecurity.staticResource`、`WebSecurityTest`
- 15 条路径，全部以 `/api/` 开头。

## 方案对比

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| A. 继续静态 | 手工维护 JSON，新增契约测试 | 零构建复杂度；Git diff 可 review；Agent 直接可读 | 路径/字段可能与实现漂移 |
| B. 构建期生成 | 维护一份路由/模型 spec，Gradle 任务生成 JSON | 契约与路由同源；CI 可校验 | 需要先建立路由元数据；生成器本身要维护 |
| C. 运行时生成 | Java 启动时根据路由表构建 OpenAPI | 不会出现文件与实现不一致 | 运行时开销与代码量；JSON 不再可 diff |

## 推荐

**短期选 A + 契约测试，中期再评估 B。**

理由：

1. 当前 API 面只有 15 条路径，手工 JSON 的可读性和 review 价值仍然很高。
2. 最容易发生的漂移是“新增 API 忘记加 `safeApiPath`”；这已经用 `OpenApiContractTest` 锁住。
3. 运行时生成（C）会牺牲 Git 可 diff 的契约，收益不足以抵消复杂度。
4. 构建期生成（B）适合在 API 稳定、路径元数据集中之后再做；届时可以把 `safeApiPath` 与 OpenAPI 路径都从同一份表生成。

## 已落地

新增 `mod/src/test/java/com/shenqianye/samsara/web/OpenApiContractTest.java`：

- 读取 `/web/openapi.json`。
- 断言 `openapi` 版本为 3.x。
- 断言每条 `paths` 都以 `/api/` 开头。
- 断言每条路径都被 `WebSecurity.safeApiPath` 接受。

这样新增公开 API 时，如果只改 JSON 不改白名单，测试会失败；反过来只改白名单不加契约，也会在 review 时被发现。

## 后续触发条件

满足任一条件时再启动 B：

- API 路径超过 40 条。
- 同一路径需要维护多个版本或多种 scope。
- 前端/Agent 开始依赖请求/响应 schema，而不只是路径列表。
