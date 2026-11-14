/** RCON 控制台纯逻辑：快捷命令、危险命令、过滤、历史与补全预览。 */

export const QUICK_GROUPS = [
  {
    label: '常用',
    items: [
      { command: 'list', label: 'list', title: '在线玩家列表' },
      { command: 'save-all', label: 'save-all', title: '保存所有世界' },
      { command: 'tps', label: 'tps', title: '查看 tick 性能' },
      { command: 'mspt', label: 'mspt', title: '查看每 tick 耗时' },
      { command: 'seed', label: 'seed', title: '查看当前世界种子' },
    ],
  },
  {
    label: '世界',
    items: [
      { command: 'samsara world list', label: 'world list', title: '世界栈列表' },
      { command: 'time query daytime', label: 'time', title: '查询时间' },
      { command: 'time set day', label: '白天', title: '设为白天' },
      { command: 'weather clear', label: '晴天', title: '清除天气' },
      { command: 'weather query', label: 'weather', title: '查询天气' },
      { command: 'difficulty', label: 'difficulty', title: '查询难度' },
    ],
  },
  {
    label: '玩家',
    items: [
      { command: 'whitelist list', label: 'whitelist', title: '白名单列表' },
      { command: 'samsara season status', label: '赛季', title: '赛季状态' },
      { command: 'samsara claim info', label: '圈地', title: '圈地信息' },
      { command: 'samsara where', label: '坐标', title: '位置查询' },
      { command: 'samsara info', label: 'samsara 信息', title: '模组信息' },
    ],
  },
  {
    label: '调试',
    items: [
      { command: 'samsara version', label: 'version', title: '版本' },
      { command: 'samsara debug reload', label: 'reload', title: '重载调试配置' },
      { command: 'samsara help', label: 'help', title: '命令帮助' },
      { command: 'samsara debug info', label: 'debug info', title: '调试信息' },
    ],
  },
  {
    label: '危险',
    danger: true,
    items: [
      { command: 'stop', label: 'stop', title: '停止服务器，需要二次确认', danger: true },
    ],
  },
];

const DANGEROUS_PREFIXES = [
  'stop', 'restart', 'ban ', 'pardon ', 'op ', 'deop ', 'kick ',
  'whitelist ', 'clear ', 'kill ', 'fill ', 'setblock ',
];

export function isDangerous(command) {
  const raw = String(command || '').trim().toLowerCase();
  if (!raw) return false;
  return DANGEROUS_PREFIXES.some((prefix) => raw === prefix.trim() || raw.startsWith(prefix));
}

export function filterQuickGroups(groups, query) {
  const text = String(query || '').trim().toLowerCase();
  if (!text) return groups;
  return groups.map((group) => ({
    ...group,
    items: group.items.filter((item) =>
      (item.command + ' ' + item.label + ' ' + item.title).toLowerCase().includes(text)),
  })).filter((group) => group.items.length);
}

export function addHistory(history, command) {
  const raw = String(command || '').trim();
  if (!raw) return history;
  if (history[0] === raw) return history;
  return [raw, ...history].slice(0, 50);
}

export function historyValue(history, index) {
  if (index < 0 || index >= history.length) return '';
  return history[index];
}

export function completePreview(command, range, item) {
  if (!range || !item) return '';
  const value = String(command || '');
  return value.slice(0, range.start) + item.text + value.slice(range.end);
}

export function responseSummary(data) {
  const ms = Number(data?.timeMs) || 0;
  return '完成 · ' + ms + ' ms' + (data?.stopping ? ' · 服务器正在停止' : '');
}
