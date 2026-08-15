# samsara 数据包

规划内容（设计文档 §4）：
- 新周目开场流程（函数：广播/出生点初始化）
- 分组传送（基于 /spreadplayers 的每组分散出生）

## 状态

- [x] pack_format = 107（26.2 的 data_major，来源：26.2 版本内 version.json 的 pack_version.data_major）
- [ ] M2 起编写 functions（mod 的 `/season start` 会调用数据包函数）
