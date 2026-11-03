---
name: windows-powershell
description: Windows PowerShell 5.1 跑命令的通用坑：中文文本文件读写编码、含空格路径的参数传递、控制台乱码，以及由此产生的“文件写坏了”假警报。
whenToUse: 在本机用 shell 读写含中文的文件、统计行数、拼接含空格或中文的路径、或输出出现乱码/行数对不上时。
---

# Windows / PowerShell 通用坑

## 编码：默认不是 UTF-8

- PS 5.1 的 `Get-Content` 不给 `-Encoding` 时按 ANSI（CP936）解码。UTF-8 无 BOM 文件不只是乱码，还会**吞换行**：中文字符的 UTF-8 末尾字节常落在 GBK 首字节区（如 `。` = `E3 80 82`，`82` 后紧跟的 `0A` 被当成双字节字符的一部分吃掉），于是多行粘成一行。
- 实测：118 行的 `SKILL.md`，`(Get-Content $p).Count` 得 **70**，`(Get-Content -Encoding UTF8 $p).Count` 得 **118**。
- 规则：
  - 读：`Get-Content -Encoding UTF8` 或 `[IO.File]::ReadAllLines($p)`。
  - 写：`[IO.File]::WriteAllText($p, $text, (New-Object System.Text.UTF8Encoding($false)))`。
  - 校验：别用默认 `Get-Content` 数行数或比对内容，会得到“文件写坏了”的假警报。
- **含中文的 `.ps1` 必须存成 UTF-8 带 BOM**（或用纯 ASCII 脚本），否则 PS 5.1 按 ANSI 解析，报“字符串缺少终止符”。编辑器/工具写出无 BOM 时，先转码再执行：
  ```powershell
  $t=[IO.File]::ReadAllText($p,[Text.Encoding]::UTF8)
  [IO.File]::WriteAllText($p,$t,(New-Object Text.UTF8Encoding($true)))
  ```

## 中文输出乱码

- 控制台代码页不是 UTF-8 时，原生程序输出会乱码：先 `[Console]::OutputEncoding = [Text.Encoding]::UTF8`，必要时 `chcp 65001`。
- `git status` 里中文文件名显示成 `实现状�?` 多半只是终端显示问题；用 read 工具或 `git status --porcelain` 核对，不要因此重命名文件。

## 含空格 / 中文的路径

- `ls E:\a b\proj` 会被拆成两个参数，报 “找不到路径”。
- 优先用 shell 工具的 `workdir` 参数隔离路径；必须内联时加引号和调用符：`& "C:\a b\x.exe"`、`Get-ChildItem -LiteralPath "C:\a b"`。
- 路径含中文、括号、`[` 时一律 `-LiteralPath`，通配符解析会静默匹配不到。

## 其他

- `$LASTEXITCODE` 只反映外部程序；cmdlet 是否成功看 `$?` 或 `$Error`。
- 原生程序写 stderr 时 PS 5.1 可能记成红色错误记录，不代表命令失败，要看退出码。
- 管道里 `Select-Object -First N` 会让上游提前终止，统计类命令别顺手加。

## 本项目补充

- 本仓库路径 `E:\workspace\deepseek harness\mc-mod-samsara` 含空格，shell 调用一律用 `workdir`，不要内联长路径。
- RCON 相关的引号/编码坑在 `mc-build-deploy-test` 里有更具体的写法（`-D` 参数必须整体加引号），优先看那份。
- 仓库根目录有证书私钥 `frp-dog.com.key`，涉及它的内容不得写进任何 skill 或文档。
