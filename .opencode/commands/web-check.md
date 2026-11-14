---
description: 跑 Web 框架守卫、单测、section E2E 和全量 E2E，并汇总证据
agent: samsara-dev
---

按顺序执行并汇总结果：

```powershell
cd web-src
npm.cmd run check
```

```powershell
node tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section <section>
```

```powershell
node tools/web-e2e.mjs --base http://127.0.0.1:8722 --token <token>
```

- `<section>` 根据本次改动选择；没有则跳过。
- 失败时先判断是否本次改动引入，再修复；不要用“已知历史问题”掩盖新失败。
- 最后输出：命令、通过数、失败项、`git status`。
