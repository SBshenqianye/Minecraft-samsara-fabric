/** 公告页纯逻辑：默认值、模型归一化、摘要与复制。与 DOM 无关，便于单测。 */

export const DEFAULT_ANNOUNCEMENT = {
  id: '',
  name: '新公告',
  enabled: true,
  trigger: 'manual',
  intervalSeconds: 300,
  startAt: '',
  target: 'all',
  targetValue: '',
  chat: true,
  chatText: '&e[公告] &f{player}，你好！',
  actionbar: false,
  actionbarText: '',
  title: false,
  titleText: '',
  subtitleText: '',
  bossbar: false,
  bossbarText: '',
  bossbarColor: 'BLUE',
  bossbarOverlay: 'PROGRESS',
  durationSeconds: 8,
  fadeInTicks: 10,
  stayTicks: 70,
  fadeOutTicks: 20,
  sound: false,
  soundId: 'minecraft:ui.toast.in',
  soundVolume: 1.0,
  soundPitch: 1.0,
  clickAction: 'none',
  clickValue: '',
  clickPermission: '',
  hoverText: '',
  priority: 0,
};

const OPTION_DEFAULTS = {
  triggers: ['manual', 'interval', 'schedule', 'join'],
  targets: ['all', 'permission', 'world', 'dimension', 'players'],
  colors: ['PINK', 'BLUE', 'RED', 'GREEN', 'YELLOW', 'PURPLE', 'WHITE'],
  overlays: ['PROGRESS', 'NOTCHED_6', 'NOTCHED_10', 'NOTCHED_12', 'NOTCHED_20'],
  clickActions: ['none', 'run', 'suggest', 'open_url', 'copy'],
};

export function createAnnouncement() {
  return JSON.parse(JSON.stringify(DEFAULT_ANNOUNCEMENT));
}

export function emptyModel() {
  return {
    announcements: [],
    history: [],
    ...OPTION_DEFAULTS,
  };
}

export function normalizeModel(data) {
  const base = emptyModel();
  if (!data || typeof data !== 'object') return base;
  return {
    ...base,
    ...data,
    announcements: Array.isArray(data.announcements) ? data.announcements : [],
    history: Array.isArray(data.history) ? data.history : [],
    triggers: Array.isArray(data.triggers) && data.triggers.length
      ? data.triggers : base.triggers,
    targets: Array.isArray(data.targets) && data.targets.length
      ? data.targets : base.targets,
    colors: Array.isArray(data.colors) && data.colors.length
      ? data.colors : base.colors,
    overlays: Array.isArray(data.overlays) && data.overlays.length
      ? data.overlays : base.overlays,
    clickActions: Array.isArray(data.clickActions) && data.clickActions.length
      ? data.clickActions : base.clickActions,
  };
}

export function summaryText(count, dirty) {
  const base = (Number(count) || 0) + ' 条公告';
  return dirty ? base + ' · 有未保存修改' : base;
}

export function duplicateAnnouncement(item) {
  const copy = JSON.parse(JSON.stringify(item || {}));
  copy.id = '';
  copy.name = (item?.name || '公告') + ' 副本';
  return copy;
}

export function previewChannels(preview) {
  return Array.isArray(preview?.channels) ? preview.channels : [];
}
