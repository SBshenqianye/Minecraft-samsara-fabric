# 测试服场景脚本

这些脚本通过 **真实 RCON + 真实停服/重启** 验证玩家会遇到的主流程，不做“只调内部方法”的假测试。
失败时会打印实际 RCON 回包；默认都指向测试服 25575，**不要**指向生产服。

## world-stack.ps1

验证世界栈与新世界入栈：

1. `world list` / `world info` 可读
2. `reset new <种子>` → 二次确认 → 停服
3. 用启动脚本重启后：新世界生成、自动入栈、出生点历史第 1 条、种子正确
4. `world leave` → 停服 → 重启后回到旧世界
5. 清理测试世界（除非 `-KeepWorld`）

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

> 切换世界会改写 `server.properties` 的 `level-name` 并停服；脚本按默认参数直接 `java -jar` 重启。
> 若测试服用的是别的启动方式/端口，用参数覆盖：
> `-ServerDir`、`-ServerArgs`、`-RconPort`、`-RconPassword`、`-Java`。
