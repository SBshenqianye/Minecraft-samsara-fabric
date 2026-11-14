# Goal: 回归锁定 + 内核纯逻辑抽取 + 静态契约评估

## 目标

按顺序完成以下工作，每项独立提交、独立验证：

1. **section E2E 锁定**：新增 `world-stack`、`sky-inventory` 两个 section，覆盖本次修复的图标/时间线/归档/背包浮窗。
2. **`app.js` 纯逻辑抽取**：配置值、搜索匹配等纯函数迁到 `web-src/src/pages/config/model.js`，补单测。
3. **0.60.1 验收**：用 section E2E + 全量 E2E + 关键页面截图/断言确认生产同版本行为。
4. **`openapi.json` 生成化评估**：给出“继续静态 / 构建期生成 / 运行时生成”的对比与建议，不直接大改。
5. **`lottery.js` / `server.js` 纯逻辑抽取**：各抽一组可测试的格式化/计算函数，保留 DOM 内核。
6. **新功能**：需要具体产品 spec；未给出前不擅自开工，只保留占位。

## 每个任务的完成门禁

```powershell
cd web-src && npm.cmd run check
```

```powershell
node tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section <section>
```

```powershell
node tools/web-e2e.mjs --base http://127.0.0.1:8722 --token <token>
```

- `npm run check`：lint + vue-tsc + vitest + build 全绿。
- 新增/修改纯逻辑必须有单测。
- 全量 E2E 保持 **281/281**，允许新增断言但不允许回退。
- 每项一个 commit，提交信息写清 `feat/test/refactor`。
- 不部署生产；部署需要用户明确指令。

## 约束

- 复杂页面保留 canvas/富文本/3D/PageSpec 内核，只抽纯逻辑。
- 不新增 `/web/*.js` 静态脚本。
- 不提交 `.tmp/`、测试服日志、临时 mapskip 副本。
- 不修改生产 `gold.syncEnabled`。
- 不删除生产备份、不清空测试服世界/玩家数据。

## 当前基线

```text
生产 webVersion  0.60.1+1114.2224
全量 E2E          281 / 281
npm run check     58 / 58
Gradle test       541 / 0
```

## 执行记录（2026-11-14）

| 项目 | 结果 | 提交 |
|---|---|---|
| 1. section E2E | 新增 `world-stack` 9/9、`sky-inventory` 5/5；联合 25/25 | `test(web): add world-stack and sky inventory section E2E` |
| 2. `app.js` 纯逻辑 | 新增 `pages/config/model.js` + 5 单测；搜索/条件显示/计数走 model | `refactor(web): extract advanced config pure model` |
| 3. 0.60.1 验收 | 生产只读验收：世界栈 3/3 图标、配置 6 文件/116 项、`/user` 5 分区 | `docs(agent): record 0.60.1 production acceptance` |
| 4. openapi 评估 | 保留静态契约 + `OpenApiContractTest` 锁路径；生成化条件写入文档 | `test(web): guard openapi paths against security whitelist` |
| 5. lottery/server 纯逻辑 | 新增 `pages/lottery/model.js`、`pages/server/model.js` + 6 单测 | `refactor(web): extract lottery and server pure models` |
| 6. 新功能 | 缺少具体 spec，暂不实现 | — |

### 最终基线

```text
npm run check       69 / 69
Gradle test        542 tests / 0 failures
section E2E        25 / 25
全量 E2E           281 / 281
生产               0.60.1+1114.2224（本轮未重新部署）
```

## 完成标志

```text
[goal:evidence] 1–5 全部提交；npm run check / section E2E / 全量 E2E 通过；openapi 评估文档提交
[goal:complete]
```

第 6 项需要产品 spec，未提供时输出：

```text
新功能缺少具体需求，暂不实现
[goal:blocked]
```
