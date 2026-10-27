---
name: version-policy
description: 维护 Minecraft/Fabric 版本策略时使用：只维护最新稳定版；低版本兼容推迟到 Web 面板之后。
whenToUse: 决定是否升级 Minecraft、Fabric Loader、Fabric API，或讨论旧版本兼容、多版本支持、版本排期时。
---

# 版本维护策略（version-policy）

## 核心决定（用户 2026-10-27）

- **只维护最新稳定版 Minecraft**。当前基线：**26.3** / Fabric Loader **0.20.1** / Fabric API **0.165.0+26.3** / Java **25**。
- **低版本兼容不在当前范围**：用户明确“兼容低版本以后再说”，优先级排在 **Web 面板之后**。
- 因此默认**不**建立 26.2/26.3 双版本分支、多版本 Gradle matrix、运行时代码兼容层，也不为旧版保留已删除/改名的 API。
- 如果用户之后改变优先级，先确认新的范围，再登记到《实现状态》〈已裁决〉一节，不要凭记忆直接开工。

## 升级到最新稳定版时必做

1. **确认版本**：以 Minecraft 官方版本清单、Fabric 发布说明、Fabric meta、Fabric API 发布页为准，不要只看旧模板。
2. **改版本参数**：`mod/gradle.properties` 的 `minecraft_version` / `loader_version` / `fabric_version` / `mod_version`。
3. **改模组元数据**：`mod/src/main/resources/fabric.mod.json` 的 `minecraft` 与 `fabricloader` 约束。
4. **编译**：`gradlew clean build`，先处理 Java/API 编译差异。
5. **启动验证**：`gradlew runServer`，确认 Fabric Loader、Fabric API、所有 mixin 注入点都能加载；编译通过不代表 mixin 目标方法仍存在。
6. **同步文档**：README 的当前版本与组件表、CHANGELOG 新版本条目、《实现状态》的核对版本与兼容性记录、《设计文档》技术选型；需要时补充《设计规范》的 API 注意事项。
7. **更新基线**：把本 skill 顶部“当前基线”改为新版本；不要保留“最新版=旧版”的过期描述。

## 版本判断口径

- “最新版本”指**最新稳定版**，不含快照/预发布/RC；用户明确要求预览版时才使用。
- 升级前提醒备份世界；升级后旧存档的区块/数据格式兼容性以实际启动验证为准。
- 低版本兼容、旧版分支、双 Jar 分发一律视为**新需求**，不是升级的默认附带项。

## 排查顺序（升级失败时）

1. 先看 `gradlew clean build` 的编译错误（API 改名/签名变化）。
2. 再看 `runServer` 的 mixin 错误（目标方法签名变化、类移动、default 方法化）。
3. 最后看 Fabric API 的移除/重构说明；不要为了绕过编译错误去改需求文档。
4. 版本策略冲突无法裁决时，停下来问用户，不要自行扩大兼容范围。
