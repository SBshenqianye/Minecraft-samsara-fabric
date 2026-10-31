# 测试服场景脚本

这些脚本通过 **真实 RCON + 真实停服/重启** 验证玩家会遇到的主流程，不做“只调内部方法”的假测试。

## world-stack.ps1

验证世界栈与 `new-world`：

1. `world list` / `world info` 可读
2. `season create <名> new-world <种子>` → `season start` 二次确认 → 停服
3. 用启动脚本重启后：新世界生成、入栈、周目 ACTIVE、种子正确
4. `world leave` → 停服 → 重启后回到旧世界
5. 清理测试世界（除非 `-KeepWorld`）

```powershell
# 先确保测试服已启动、RCON 监听 25575
powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke\world-stack.ps1 -Confirm

# 保留现场
powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke\world-stack.ps1 -Confirm -KeepWorld
```

> 切换世界会改写 `server.properties` 的 `level-name` 并停服；脚本按默认参数直接 `java -jar` 重启。
> 若测试服用的是别的启动方式/端口，用参数覆盖：
> `-ServerDir`、`-ServerArgs`、`-RconPort`、`-RconPassword`、`-Java`。
>
> 生产服 RCON 默认关闭；**不要**把 `-RconPort` 指向生产服。
