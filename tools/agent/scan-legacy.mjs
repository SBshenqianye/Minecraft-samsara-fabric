#!/usr/bin/env node
// 扫描旧管理端脚本的 DOM / 全局 / API 依赖，生成迁移清单。
// 用法：node tools/agent/scan-legacy.mjs [--out docs/agent]

import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const webDir = path.join(root, 'web-src/src/legacy-core');
const args = process.argv.slice(2);
const outArg = args.indexOf('--out');
const outDir = path.join(root, outArg >= 0 && args[outArg + 1] ? args[outArg + 1] : 'docs/agent');

const PAGE_MAP = {
  'app.js': { page: 'config / claim-range / 全局壳', route: '/samsara/config/*', note: '核心旧壳，已作为 Vite 内核打包，不再走静态脚本' },
  'config-editor.js': { page: '配置编辑', route: '/samsara/config/editor', note: 'PageSpec/FormSpec，最高复杂度' },
  'output.js': { page: '输出格式', route: '/samsara/config/output', note: 'contenteditable 富文本编辑器，中等复杂度' },
  'identity.js': { page: '身份组', route: '/samsara/community/identity', note: '列表 + 详情 + 背包 iframe' },
  'map.js': { page: '地图', route: '/samsara/world/map', note: 'canvas/瓦片，保留渲染内核' },
  'world-stack.js': { page: '世界栈', route: '/samsara/world/stack', note: '时间线 + 3D/图标' },
  'lottery.js': { page: '经济业务', route: '/samsara/economy', note: '金额/轮次/下注' },
  'server.js': { page: '本体服务器', route: '/vanilla/*', note: '建议拆成 4 个页面' },
  'chat-text.js': { page: '共享富文本', route: '—', note: '共享组件，先抽公用' },
  'structured.js': { page: '共享结构化表单', route: '—', note: '共享组件' },
  'form-kit.js': { page: '共享表单工具', route: '—', note: '共享组件' },
  'page-kit.js': { page: '旧页面生命周期', route: '—', note: '已随 Vite 内核打包，页面走 Vue 生命周期' },
  'item-ui.js': { page: '共享物品 UI', route: '—', note: '已有 Vue ItemIcon/ItemTooltip' },
  'value-table.js': { page: '共享价值表', route: '—', note: '可复用' },
  'particles.js': { page: '共享粒子背景', route: '—', note: '视觉资源' },
  'banners.js': { page: '共享横幅/粒子', route: '—', note: '视觉资源' },
  'user.js': { page: '公开用户端内核', route: '/user', note: '公开只读 API 适配，已由 UserPage.vue 挂载' },
};

const STATUS_MAP = {
  'output.js': 'shell-migrated',
  'server.js': 'shell-migrated',
  'lottery.js': 'shell-migrated',
  'identity.js': 'shell-migrated',
  'config-editor.js': 'shell-migrated',
  'map.js': 'shell-migrated',
  'world-stack.js': 'shell-migrated',
  'app.js': 'bundled',
  'page-kit.js': 'bundled',
  'chat-text.js': 'bundled',
  'structured.js': 'bundled',
  'form-kit.js': 'bundled',
  'item-ui.js': 'bundled',
  'value-table.js': 'bundled',
  'particles.js': 'bundled',
  'banners.js': 'bundled',
  'user.js': 'bundled',
};

const STATUS_LABEL = {
  migrated: '✅ migrated',
  'shell-migrated': '🧩 shell-migrated',
  'in-progress': '🚧 in-progress',
  pending: '⏳ pending',
  shared: '🔗 shared',
  shell: '🧱 shell',
  bundled: '📦 bundled',
};

function unique(values) {
  return [...new Set(values)].sort();
}

function scanFile(file) {
  const full = path.join(webDir, file);
  if (!fs.existsSync(full)) return null;
  const text = fs.readFileSync(full, 'utf8');
  const size = Buffer.byteLength(text, 'utf8');

  // DOM 依赖：getElementById、旧脚本的 $('id') 帮助函数、querySelector('#id')。
  const idMatches = [
    ...text.matchAll(/getElementById\(\s*['"]([^'"]+)['"]/g),
    ...text.matchAll(/\$\(\s*['"]([A-Za-z][\w:-]*)['"]\s*\)/g),
    ...text.matchAll(/querySelector(?:All)?\(\s*['"]#([A-Za-z][\w:-]*)/g),
  ];
  const ids = unique(idMatches.map((match) => match[1]));
  const domReads = idMatches.length;
  const panelAttrs = unique([
    ...text.matchAll(/data-(?:server-)?panel=["']([^"']+)["']/g),
  ].map((match) => match[1]));
  const panels = unique([...ids, ...panelAttrs].filter((id) => id.startsWith('panel-')
    || id.startsWith('server-')));

  const globals = unique([...text.matchAll(/window\.(Samsara[A-Za-z0-9_]+)/g)].map((m) => m[1]));
  const apis = unique([...text.matchAll(/['"`](\/api\/[^'"`\s?)]+)/g)].map((m) => m[1]));
  const registerPages = unique([...text.matchAll(/registerPage\(\s*['"]([^'"]+)['"]/g)].map((m) => m[1]));
  const events = [...text.matchAll(/addEventListener\(/g)].length;
  const innerHtml = [...text.matchAll(/innerHTML/g)].length;
  const info = PAGE_MAP[file] || { page: '未分类', route: '—', note: '' };
  const status = STATUS_MAP[file] || 'pending';
  const risk = Math.round(size / 1024)
    + ids.length * 2
    + domReads
    + globals.length * 3
    + innerHtml * 5
    + (info.note.includes('最高') ? 20 : 0);
  return {
    file,
    sizeKB: Math.round(size / 1024 * 10) / 10,
    page: info.page,
    route: info.route,
    note: info.note,
    status,
    statusLabel: STATUS_LABEL[status] || status,
    ids,
    domReads,
    panels,
    globals,
    apis,
    registerPages,
    events,
    innerHtml,
    risk,
  };
}

const files = fs.readdirSync(webDir)
  .filter((file) => file.endsWith('.js'))
  .map(scanFile)
  .filter(Boolean)
  .sort((a, b) => b.risk - a.risk);

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'legacy-inventory.json'), JSON.stringify({
  generatedAt: new Date().toISOString(),
  files,
}, null, 2) + '\n', 'utf8');

const lines = [
  '# Legacy Web 迁移清单',
  '',
  '> 由 `node tools/agent/scan-legacy.mjs` 生成，提交前重新生成。',
  `> 扫描时间：${new Date().toISOString()}`,
  '',
  '## 总览',
  '',
  '| 文件 | 状态 | KB | 页面 | 路由 | DOM 读取 | DOM id | 全局 | API | innerHTML | 风险分 | 建议 |',
  '|---|---|---:|---|---|---:|---:|---:|---:|---:|---:|---|',
  ...files.map((file) => `| \`${file.file}\` | ${file.statusLabel} | ${file.sizeKB} | ${file.page} | ${file.route} | ${file.domReads} | ${file.ids.length} | ${file.globals.length} | ${file.apis.length} | ${file.innerHtml} | ${file.risk} | ${file.note} |`),
  '',
  '## 状态汇总',
  '',
  ...['migrated', 'shell-migrated', 'in-progress', 'pending', 'shared', 'shell', 'bundled'].map((status) => {
    const list = files.filter((file) => file.status === status);
    if (!list.length) return null;
    return `- ${STATUS_LABEL[status]}：${list.map((file) => '`' + file.file + '`').join('、')}`;
  }).filter(Boolean),
  '',
  '## 按迁移批次',
  '',
  '### 第一批（低风险表单/只读）',
  '',
  ...files.filter((file) => ['announcements.js', 'output.js', 'permissions.js', 'identity.js'].includes(file.file)
    && file.status !== 'migrated')
    .map((file) => `- \`${file.file}\` → ${file.route}`),
  '',
  '### 第二批（经济 / RCON）',
  '',
  ...files.filter((file) => ['lottery.js', 'server.js'].includes(file.file)
    && file.status !== 'migrated')
    .map((file) => `- \`${file.file}\` → ${file.route}`),
  '',
  '### 第三批（配置编辑器）',
  '',
  ...files.filter((file) => file.file === 'config-editor.js' && file.status !== 'migrated')
    .map((file) => `- \`${file.file}\` → ${file.route}`),
  '',
  '### 第四批（canvas / 3D）',
  '',
  ...files.filter((file) => ['map.js', 'world-stack.js', 'app.js'].includes(file.file)
    && file.status !== 'migrated')
    .map((file) => `- \`${file.file}\` → ${file.route}`),
  '',
  '## 明细',
  '',
];

for (const file of files) {
  lines.push(`### \`${file.file}\``);
  lines.push('');
  lines.push(`- 状态：${file.statusLabel}`);
  lines.push(`- 页面：${file.page}（${file.route}）`);
  lines.push(`- 大小：${file.sizeKB} KB；DOM 读取：${file.domReads}；事件监听：${file.events}；innerHTML：${file.innerHtml}`);
  if (file.note) lines.push(`- 建议：${file.note}`);
  if (file.panels.length) lines.push(`- panel：${file.panels.map((id) => '`' + id + '`').join('、')}`);
  if (file.globals.length) lines.push(`- 全局：${file.globals.map((id) => '`' + id + '`').join('、')}`);
  if (file.registerPages.length) lines.push(`- registerPage：${file.registerPages.map((id) => '`' + id + '`').join('、')}`);
  if (file.apis.length) lines.push(`- API：${file.apis.map((id) => '`' + id + '`').join('、')}`);
  if (file.ids.length) {
    const shown = file.ids.slice(0, 30);
    lines.push(`- DOM id（${file.ids.length}）：${shown.map((id) => '`' + id + '`').join('、')}${file.ids.length > shown.length ? ' …' : ''}`);
  }
  lines.push('');
}

fs.writeFileSync(path.join(outDir, 'legacy-inventory.md'), lines.join('\n') + '\n', 'utf8');
console.log('legacy inventory written to ' + path.relative(root, path.join(outDir, 'legacy-inventory.md')));
console.log('files=' + files.length + ' riskTop=' + files.slice(0, 5).map((file) => file.file + ':' + file.risk).join(', '));
