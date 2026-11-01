# 测试服场景脚本

这些脚本通过 **真实 RCON + 真实停服/重启** 验证玩家会遇到的主流程，不做“只调内部方法”的假测试。
失败时会打印实际 RCON 回包；默认都指向测试服 25575，**不要**指向生产服。

## world-stack.ps1

验证 world=栈顶、reset new 生成栈维度：

1. `world list` / `world info` 可读
2. `reset new <种子> [levelType] [archiveData]` → 二次确认 → 停服
3. 重启后：旧 world 变成 `stack_<n>` 维度，新 `world` 生成、出生点历史第 1 条、种子正确
4. `world` 始终是栈顶；旧栈可用 `world enter <stack_n>` 直接传送
5. v0.24 不做自动删除，测试层默认保留（手动清理 `world/dimensions/samsara/stack_<n>` 与 `world/samsara/stack/stack_<n>`）

```powershell
# 先确保测试服已启动、RCON 监听 25575
powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke\world-stack.ps1 -Confirm

# 保留现场
powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke\world-stack.ps1 -Confirm -KeepWorld
```

## world-reset.ps1

验证同世界重置：

1. `reset same` 两次确认
2. 等待倒流完成，出生点历史追加 `same-world-reset`
3. 连续重置后新出生点与上一次保持最小间距（不聚集）
4. 保护集仍存在

> 脚本会临时把测试服的 `rewind` 改为 5s、`centermin/centermax` 调小，结束后恢复默认 26h/15000/40000。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke\world-reset.ps1 -Confirm -Resets 3

# 在指定栈层（假玩家当前所在层）重置：先 world enter 再以该玩家身份执行
powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke\world-reset.ps1 -Confirm -BotName StackBot -Layer stack_1
```

## world-legacy-migrate.ps1

旧 `world` + `world_s1` + `world_s2` 布局 → v0.24（world=栈顶、旧层=stack_<n>）迁移：

1. 启动服务器（要求先停服、`world/samsara/stack.json` 不存在）
2. `world list` 出现 `stack_0 → stack_1 → world`
3. `world/dimensions/samsara/stack_<n>` 与 `world/samsara/stack/stack_<n>/` 元数据存在
4. `players/data`、claims/diff/track/spawn-history 保留
5. `server.properties` 固定 `level-name=world`

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke\world-legacy-migrate.ps1 -Confirm -ServerDir test-server-stack
```

## world-chain.ps1

用 PuppetPlayers 假玩家验证跨世界传送链：

1. 主世界虚空 → 同世界下界（F-29）
2. 下界顶部 → 同世界主世界（F-31）
3. 当前不是栈顶：主世界顶部 → 上层世界下界（F-32）
4. 当前不是栈底：下界虚空 → 下层世界主世界（F-30）
5. 当前是栈底：下界虚空 → 原版死亡/重生（F-34）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke\world-chain.ps1 -Confirm -BotName ChainBot
```

> 脚本默认等待 4.2 秒，覆盖 3 秒跨世界防抖；F-32 需要先 `world enter` 到非栈顶层。

## 自动加载 / 重试（v0.27）

目标栈层启动时加载失败时，玩家沿跨栈链到达会自动重试（5s→60s 退避），不再只提示“未加载”。测试可用：

```powershell
# 启动时注入失败（仅测试）：java ... -Dsamsara.debug.failStackLayer=stack_1 ...
# 运行中注入/解除
samsara debug world failstack stack_1 on
samsara debug world failstack stack_1 off
# 忽略退避立即重试，并返回失败原因
samsara debug world retrystack stack_1
# 查看各层加载状态与失败原因
samsara debug world multi
```

> v0.24 的 `world enter/leave` 直接传送到栈维度，不再改写 `server.properties`；`reset new` 仍会改 `level-seed`/`level-type` 并停服，脚本按默认参数直接 `java -jar` 重启。
> 若测试服用的是别的启动方式/端口，用参数覆盖：
> `-ServerDir`、`-ServerArgs`、`-RconPort`、`-RconPassword`、`-Java`。
