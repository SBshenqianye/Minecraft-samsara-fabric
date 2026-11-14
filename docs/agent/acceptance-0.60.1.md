# 0.60.1 生产验收记录

> 日期：2026-11-14  
> 地址：`http://127.0.0.1:8720`  
> 版本：`webVersion=0.60.1+1114.2224`

## 只读页面验收

用浏览器实际加载生产页面，注入管理端 token，不执行写操作。

### 世界栈 `/samsara/world/stack`

```json
{
  "cards": 3,
  "visuals": 3,
  "frames": 3,
  "badge": "出生点地图",
  "source": "map",
  "errors": []
}
```

- 每张卡片都有 16:9 图标视觉区。
- 回退来源为出生点地图。
- 没有未捕获脚本错误。

### 高级配置 `/samsara/config/advanced`

```json
{
  "files": 6,
  "entries": 116,
  "summary": "116 / 116 项",
  "errors": []
}
```

- 6 个配置文件、116 项全部渲染。
- `app.js` 纯逻辑抽取后搜索/计数/条件显示未回归。

### 公开用户端 `/user`

```json
{
  "sections": 5,
  "mapCanvas": true,
  "errors": []
}
```

- 五个公开分区全部由 Vue 渲染。
- 地图 canvas 可用。

## 接口验收

```text
/api/lottery    -> 200
/api/containers -> 200
/api/track      -> 200
/api/status     -> 200
/user           -> 200
/               -> 200
/openapi.json   -> 200

/legacy.html    -> 404
/app.js         -> 404
/style.css      -> 404
```

## 自动化基线

```text
npm run check       63 / 63
Gradle test        541 tests / 0 failures
section E2E        25 / 25（legacy-shell + world-stack + sky-inventory）
全量 E2E           281 / 281
```

## 生产进程

```text
version=0.41.2
webVersion=0.60.1+1114.2224
prod jar SHA-256=91F2B206A26C740FDAE974146E21C77FCFB897C7C6C3696DFB86CB8F5F5B3D8A
```
