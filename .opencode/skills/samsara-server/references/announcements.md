# 公告系统

## 模型

`GET /api/announcements` 返回：

```json
{
  "ok": true,
  "announcements": [
    {
      "id": "welcome",
      "name": "欢迎公告",
      "enabled": false,
      "trigger": "join",
      "target": "all",
      "chat": true,
      "chatText": "&e[公告] &f欢迎 {player} 来到服务器！\\n&7在线 {online} 人"
    }
  ],
  "variables": ["player", "display", "world", "dimension", "online", "time", "date", "prefix"]
}
```

## 字段

- `trigger`：`manual` / `interval` / `schedule` / `join`
- `intervalSeconds`：interval 触发，最小 10
- `startAt`：schedule 触发，`yyyy-MM-dd HH:mm`
- `target`：`all` / `permission` / `world` / `dimension` / `players`
- `targetValue`：权限路径 / 维度 / 逗号分隔玩家名
- 展示：`chat` + `chatText`、`actionbar` + `actionbarText`、`title` + `titleText` + `subtitleText`、`bossbar` + `bossbarText`、`sound` + `soundId`
- BossBar：`bossbarColor`、`bossbarOverlay`、`durationSeconds`
- Title：`fadeInTicks`、`stayTicks`、`fadeOutTicks`
- 声音：`soundId`、`soundVolume`、`soundPitch`
- 交互：`clickAction`（none/run/suggest/open_url/copy）、`clickValue`、`clickPermission`、`hoverText`
- `priority`

## 发送

```json
// 1) 预览
{ "id": "welcome", "dryRun": true }

// 2) 确认
{ "id": "welcome", "dryRun": false, "confirm": true }
```

`player` 可指定单个测试玩家；留空按 `target`。

## 安全

- 全服公告必须得到用户明确确认。
- `clickAction=run` 默认要求 OP；没有把握时不要配置命令点击。
- BossBar 可用 `/api/announcements/stop` 立即停止。
