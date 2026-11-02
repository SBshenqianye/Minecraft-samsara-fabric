# Prompt：上层栈下界虚空 → 下层栈主世界改为高空坠落（防空乒乓）

你在仓库 `E:\workspace\deepseek harness\mc-mod-samsara` 工作。先阅读：

- `docs/需求文档.md` 的 F-29~F-34〈跨世界传送链〉
- `docs/设计文档.md` 的 §7.3〈跨世界往返（统一传送机制）〉
- `docs/设计规范.md` 的〈栈层加载与重试〉〈共享表单框架〉
- `docs/实现状态.md` 的 1-2、F-29~F-34 与修订历史
- `AGENTS.md` 与 `.agents/skills` 下的 `mc-build-deploy-test`、`docs-maintainer`、`version-policy`

当前版本是 **v0.34.0**，生产服已部署 `samsara-0.34.0.jar`。本次只改跨世界传送的一个落点，不要动权限、世界栈导入/时间线、身份组等其他功能。

## 目标

把这条路径：

> 上层栈的下界虚空（`y < minY - 32`）→ 下层栈的主世界

从现在的**地表安全点**改为**主世界高空空中落点**，让玩家从高空掉进下层主世界。

其他三条路径保持不变：

- 主世界虚空 → 同层下界（仍为下界安全点）；
- 下界顶部 → 同层主世界（仍为地表安全点）；
- 主世界顶部 → 上层栈下界（仍为下界基岩顶上方）。

栈底下界的虚空仍然交给原版死亡，不拦截。

## 当前代码事实（先核实，不要直接假设）

- `mod/src/main/java/com/shenqianye/samsara/season/WorldChainTeleport.java`
  - `check()` 的 `the_nether` 虚空分支：
    ```java
    int tx = WorldChainRules.toOverworld(player.getX());
    int tz = WorldChainRules.toOverworld(player.getZ());
    teleport(player, target, lower, tx, tz, safeY(target, tx, tz), ...);
    ```
  - `safeY()` 会 `getChunk()` 后取 `MOTION_BLOCKING` 高度图，返回 `h + 1`，并做干地/±12 格搜索/顶部向下兜底；它现在是**地表点**。
  - `netherRoofY()` 只用于“主世界顶部 → 上层下界”，落在下界基岩顶上方。
- `mod/src/main/java/com/shenqianye/samsara/season/WorldChainRules.java`
  - 虚空阈值 `y < minY - 32`；
  - 主世界顶部阈值 `y > minY + height`；
  - 下界顶部阈值 `y > minY + height - 1`；
  - `toOverworld(netherX) = round(x * 8)`。
- `StackTeleport.safeY()` 是 `/world enter` 等命令用的地表落点，**不要改**。
- `docs/设计文档.md` §7.3 目前同时写着“下层主世界地表安全点”和“传送到下层世界的主世界空中”，口径冲突；本次以“高空坠落”为准，统一文档。
- `WorldChainTeleport` 每 5 tick 检查一次，单玩家传送冷却 3 秒。

## 必须实现的落点策略

### 1. 纯逻辑落点函数

新增一个可单测的纯函数（可放在 `WorldChainRules` 或新类 `VoidDropPolicy`）：

```java
/** 下界虚空 → 下层主世界的高空落点；必须低于主世界顶部触发阈值。 */
public static int voidDropY(int minY, int height) { ... }
```

要求：

- 返回 `minY + height - 16`（26.3 主世界 = 320 - 16 = 304）左右的高空 Y；
- 必须 `clamp` 到 `minY + 2 .. minY + height - 2`；
- **必须严格小于 `minY + height`**，否则会触发“主世界顶部 → 上层下界”，把玩家弹回上层下界（乒乓）；
- 如果以后有自定义维度高度，按传入的 `minY/height` 计算，不写死 320。

### 2. 传送分支

只把 `WorldChainTeleport` 的 `the_nether` 虚空分支改为：

```java
ServerLevel target = StackTeleport.level(server, lower, "overworld");
...
target.getChunk(tx >> 4, tz >> 4);          // 先确保目标区块生成/加载
player.resetFallDistance();                  // 清掉下界虚空累积的摔落距离
int dropY = WorldChainRules.voidDropY(
        target.dimensionType().minY(), target.dimensionType().height());
teleport(player, target, lower, tx, tz, dropY, "下界虚空 → 下层 ... 主世界高空");
```

- `getChunk` 失败时降级到现有 `safeY()`，并写 WARN，不能把玩家扔进未加载区块；
- 落点必须是空气，不能落在方块里；必要时做 2~3 格向上找空气；
- 保留现有 3 秒冷却、个人重生点更新、提示文本与日志；
- 不改 `teleport()` 的通用逻辑，除非为了清 `fallDistance` / 加防乒乓标记。

### 3. 防乒乓（重点）

必须同时满足：

1. **落点 Y 低于顶部阈值**：`dropY < minY + height`，建议 `height - 16`，禁止使用 `maxY - 1` / `maxY`。
2. **到达守卫**：传送成功后给该玩家一个 `voidDropGuardUntil`（例如 `now + 5000ms`）或 `justDroppedFromVoid=true`；在守卫期内，`check()` 跳过“主世界顶部 → 上层下界”的判断。
   - 守卫只抑制“主世界顶部 → 上层”这一条，不影响玩家主动从下层主世界掉入虚空（那应该继续往下层下界）；
   - 守卫建议 5 秒，覆盖慢降/区块加载导致的短暂 Y 抖动；
   - 守卫按玩家 UUID 记录，清理逻辑与现有 `lastTeleport`/`lastNotice` 一致。
3. **慢降/坠落体验**：默认建议给 `slow_falling`（例如 60 秒、无粒子、无环境音）避免 240+ 格自由落体必死；如果服务端希望保留真实摔落伤害，提供配置关闭。
   - 配置建议放在 `config/samsara/commands.yml` 的新分组“传送链”：
     - `chain.voidDropSlowFalling`（bool，默认 true）
     - `chain.voidDropSlowFallingSeconds`（int，默认 60，范围 0~300）
   - 如果不想扩配置，至少把常量写清楚并同步文档；但默认不能让玩家每次都被摔死。
4. **守卫过期后正常上行**：玩家之后主动搭方块到主世界顶部，仍应触发“主世界顶部 → 上层下界”，不能因为永久标记而失效。

### 4. 不要改的路径

- `主世界虚空 → 同层下界`：保持 `safeNetherY()`。
- `下界顶部 → 同层主世界`：保持 `safeY()` 地表。
- `主世界顶部 → 上层下界`：保持 `netherRoofY()` 下界屋顶。
- 栈底下界虚空：保持原版死亡。
- `/samsara world enter|leave` 和 `StackTeleport.safeY()`：不改。

## 测试与验收

### 单元测试

新增/更新：

- `voidDropY` 的 min/max/clamp、低于顶部阈值、不同高度维度；
- 防乒乓纯逻辑：守卫期内 `isTop` 被抑制，守卫过期后恢复；
- 现有 `WorldChainRulesTest` 全绿。

### 集成/临时测试服

用两层栈（`world` + `stack_0` 或 `stack_1`）：

1. 把测试玩家放到上层下界 `y < minY - 32`，触发一次；
2. 断言目标维度是下层 `overworld`，Y 在 `minY + height - 16` 附近；
3. **等待至少 10 秒 / 若干检查周期**，断言玩家没有被弹回上层下界（防乒乓）；
4. 在守卫过期后把玩家放到下层主世界顶部以上，断言仍能正常上行到上层下界（防永久抑制）；
5. 栈底下界虚空仍然 `fell out of the world`/原版死亡；
6. 另外三条路径各回归一次，Y 行为不变；
7. `gradle build` 0 失败。

### 文档

- `docs/设计文档.md` §7.3：统一改为“下界虚空 → 下层主世界高空坠落”，删除“地表安全点/空中”并存的矛盾；
- `docs/实现状态.md` 1-2 / F-29~F-34：更新实现说明、Y 策略、防乒乓守卫与测试证据；追加修订历史一行；
- `CHANGELOG.md`：按版本策略新增一条（建议 v0.34.1 或 v0.35.0，取决于是否新增配置项）；
- `README.md` 的〈跨栈自动链〉如用户可见行为变化明显，补一句“虚空下坠会从下层主世界高空落下”；
- `docs/需求文档.md` 的 F-30 若原先写“地表”，同步为“高空坠落”，并注明防乒乓。

## 非目标

- 不做传送门/载具/鞘翅；
- 不改下界顶部、主世界顶部、同层虚空三条路径；
- 不改世界栈导入、权限、身份组、Web UI；
- 不做跨栈链的分支/回滚。

## 交付

- 代码 + 单测 + 文档；
- 构建 `samsara-<新版本>.jar`，测试服验证通过后，再按 `mc-build-deploy-test` 的停服窗口部署生产服；
- 生产服部署前确认：落点 Y、慢降开关、防乒乓守卫、栈底原版死亡、其他三条路径全部回归。
