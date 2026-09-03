# ADR-002: 离线 UUID 与正版 UUID 碰撞分析

> 日期：2026-09-07
> 状态：已采纳
> 关联：设计文档 §9.2.3

## 问题

离线 UUID（type 3, MD5）与正版 UUID（type 4, random）是否存在碰撞可能？

## 分析

根据 RFC 4122，UUID 的 version 位（第 13 个 hex 字符的高 4 位）区分生成算法：

| 类型 | Version 位 | 格式特征 |
|---|---|---|
| 离线 UUID (type 3, MD5) | `3` | `xxxxxxxx-xxxx-3xxx-yxxx-xxxxxxxxxxxx` |
| 正版 UUID (type 4, random) | `4` | `xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx` |

Version 位由标准固定，type-3 和 type-4 的格式互斥。

## 结论

**碰撞概率为零**。无需额外防护。
