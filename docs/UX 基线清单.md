# UX 基线清单

> 2026-11-18 建立。范围：`web-src/src/router.js` 的 57 条路径/别名（守卫 `check-routes.mjs` 输出）。
> 口径：每一项只写“能被谁验证”，不写“看起来还行”。

## 检查项

| 代号 | 检查内容 | 可接受的证据 |
|---|---|---|
| L | 加载态：首屏有占位/提示，不是空白 | 页面 `PageState`/`loading` 分支；E2E 等到元素出现 |
| E | 空态：没有数据时说明“为什么空、下一步做什么” | 页面空态文案；E2E 断言 |
| X | 错误态：读取失败明确报错，不伪装成空数据 | `PageState error` + E2E 断网/失败分支 |
| D | 禁用态：未连接/无权限/保存中按钮不可点 | `:disabled` 绑定；E2E |
| C | 危险操作二次确认 | `confirmDanger` / `ElMessageBox`；E2E 断言确认框 |
| N | 窄屏（≈390px）：不横向溢出，表格/长 ID 可滚动 | 人工 + 响应式 CSS |
| K | 键盘：Tab 可达，焦点环可见 | 人工 + `polish.css` 焦点环 |
| T | 对比度：暗色下文字/边框仍可读 | 人工抽查 + tokens |
| S | 长文本：长名字/长 ID/多行文本不撑破布局 | 截图 + CSS `min-width:0`/截断 |
| U | 未保存修改：离开页面前确认 | `useUnsavedChanges` + 路由守卫 |

## 分组现状

### 工作区导航 / 公开端

| 页面 | L | E | X | D | C | N | K | T | S | U | 证据 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `/user` 公开只读 | ✅ | ✅ | ✅ | — | — | 人工 | 共享 | 抽查 | 共享 | — | E2E 用户端 6 项 |
| 登录/token 对话框 | ✅ | — | ✅ | ✅ | — | 共享 | 共享 | 抽查 | 共享 | — | E2E 连接流程 |
| 404 | ✅ | — | — | — | — | 共享 | 共享 | 抽查 | — | — | 路由兜底 |

### 本体（server / vanilla）

| 页面 | L | E | X | D | C | N | K | T | S | U | 证据 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 服务器配置 `server/config` | ✅ | ✅ | ✅ | ✅ | ✅ 删除/重读 | 共享 | 共享 | 抽查 | ✅ 限宽 | ✅ | E2E server 分区 |
| 存档备份 `server/backup` | ✅ | ✅ | ✅ | ✅ | ✅ 删除/恢复 | 共享 | 共享 | 抽查 | ✅ | — | E2E server 分区 |
| RCON `server/console` | ✅ | ✅ | ✅ | ✅ | — | 共享 | 共享 | 抽查 | ✅ 等宽 | — | E2E server-console |
| Web 审计 `system/audit` | ✅ | ✅ | ✅ | ✅ | — | 共享 | 共享 | 抽查 | ✅ | — | E2E server 分区 |
| 模组配置 `mods/:modId` | ✅ | ✅ | ✅ | ✅ | ✅ 危险键 | 共享 | 共享 | 抽查 | ✅ | ✅ | E2E config-editor |

### 经济业务

| 页面 | L | E | X | D | C | N | K | T | S | U | 证据 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 概览 `economy` | ✅ | ✅ | ✅ | ✅ | — | 共享 | 共享 | 抽查 | ✅ | — | E2E lottery |
| 基金 `economy/fund` | ✅ | ✅ | ✅ | ✅ | ✅ 写回 | 共享 | 共享 | 抽查 | ✅ | ✅ 槽位草稿 | E2E lottery（排序只读视图） |
| 乐透 `economy/lottery` | ✅ | ✅ | ✅ | ✅ | ✅ 取消/开奖 | 共享 | 共享 | 抽查 | ✅ | — | E2E lottery |
| 保险 `economy/insurance` | ✅ | ✅ | ✅ | ✅ | ✅ 审核 | 共享 | 共享 | 抽查 | ✅ | — | E2E lottery |

### 天网 / 世界

| 页面 | L | E | X | D | C | N | K | T | S | U | 证据 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 轨迹 `sky/track` | ✅ | ✅ | ✅ | ✅ | — | 人工 | 共享 | 抽查 | ✅ | — | E2E batch5 |
| Diff `sky/diff` | ✅ | ✅ | ✅ | ✅ | — | 人工 | 共享 | 抽查 | ✅ | — | E2E batch5 |
| 容器/背包 `sky/containers` | ✅ | ✅ | ✅ | ✅ | — | 人工 | 共享 | 抽查 | ✅ | — | E2E batch5 |
| 世界栈 `world/stack` | ✅ | ✅ | ✅ | ✅ | ✅ 导入/归档 | 共享 | 共享 | 抽查 | ✅ | — | E2E world-stack |
| 地图 `world/map` | ✅ | ✅ | ✅ | — | — | 人工 | 共享 | 抽查 | — | — | E2E map |

### 社区 / 配置

| 页面 | L | E | X | D | C | N | K | T | S | U | 证据 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 公告 `community/announcements` | ✅ | ✅ | ✅ | ✅ | ✅ 删除 | 共享 | 共享 | 抽查 | ✅ | ✅ 草稿 | E2E announcements |
| 身份组 `community/identity` | ✅ | ✅ | ✅ | ✅ | ✅ 重绑/移除 | 共享 | 共享 | 抽查 | ✅ | ✅ 草稿 | E2E identity |
| 输出格式 `config/output` | ✅ | ✅ | ✅ | ✅ | ✅ 恢复默认 | 共享 | 共享 | 抽查 | ✅ | ✅ 模板草稿 | E2E output |
| 配置编辑器 `config/editor` | ✅ | ✅ | ✅ | ✅ | ✅ 写入确认 | 共享 | 共享 | 抽查 | ✅ | ✅ 草稿 | E2E config-editor |
| 权限 `config/permissions` | ✅ | ✅ | ✅ | ✅ | ✅ 危险权限 | 共享 | 共享 | 抽查 | ✅ | — | E2E permissions |
| 高级配置/圈地 `config/advanced` | ✅ | ✅ | ✅ | ✅ | ✅ 删除旗帜 | 共享 | 共享 | 抽查 | ✅ | — | E2E app-config |

说明：
- 共享组件（`PageState`、`PanelHead`、`Segmented`、`ItemWorkbench`、`TabShell`）在本轮 UI 统一后由全站复用，所以 L/E/X/D/K/T/S 在页面级不再各写一套。
- N（窄屏）与 T（暗色）本轮只做了共享 CSS 适配与抽查；逐页人工截图仍是遗留项，见下。

## 本轮修复（2026-11-18 第二轮）
| 问题 | 修法 | 证据 |
|---|---|---|
| 页面间跳转会静默丢掉未保存修改 | 新增 `useUnsavedChanges` + `router.beforeEach` 统一确认；配置编辑器、公告、输出、身份、服务器配置、基金仓库已接入 | `useUnsavedChanges.test.js` 3 项；E2E 导航不再被弹窗卡住（`window.__e2e` 仅测试环境跳过） |
| 基金“排序”会改写槽位并保存 | 排序只改列表视图 | E2E `基金排序只改视图，不改槽位` |
| 概览历史走势全 0 仍画空白柱 | 无金额时显示说明 | E2E 截图 + 单测 |
| 公告/权限/乐透/服务器读取失败显示为空 | `PageState` 错误态 + 重读确认 | E2E server/announcements |

## 自动化巡检（2026-11-19）

新增 `tools/agent/ux-sweep.mjs`：390×844 视口 + `prefers-color-scheme: dark` + `prefers-reduced-motion`，逐路由检查：

- 页面级横向溢出：`document.documentElement.scrollWidth > innerWidth + 1`；
- 可见焦点元素数量、是否存在正 `tabindex`；
- 关键文字与有效背景的对比度（< 3.0 记为问题）；
- 未捕获脚本错误。

22 条代表路由的结果：**0 页面级溢出、0 正 tabindex、0 脚本错误**。
唯一真实对比度问题是选中态白字压在青绿底上（1.86:1），已统一改为 `var(--accent-ink)`：

- `.section-tab.active`、`.el-segmented__item-selected`
- `CreativeLibrary` / `InventoryWindow` / `ModeSwitch` / `ContainerPage` / `pattern-direction-cell.active`

内部滚动容器（长 ID、地图视口、世界栈文件树）会出现在 `offenders` 里，但它们自身可滚动，不算页面溢出。

## 遗留（未打勾，不假装完成）

1. **N/T 逐页人工截图**：自动化巡检已覆盖 22 条代表路由的 390px 溢出与暗色对比度，但全站 57 条路径仍未逐张截图归档。
2. **K 键盘全流程**：无正 tabindex、焦点环已统一；复杂拖拽（身份组卡片、基金格子）仍没有键盘替代操作。
3. **长文本边界**：极端长玩家名/MOTD/模组 ID 只在样例数据下验证。
4. 以上遗留都回到 `docs/待办.md` 的 G-01/G-03 治理项。
