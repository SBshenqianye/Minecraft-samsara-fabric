#!/usr/bin/env node
// 生成一个 Vue 页面骨架 + 纯逻辑模型 + 单测 + 迁移任务 spec。
// 用法：
//   node tools/agent/scaffold-page.mjs --name announcements --title 公告 --route /samsara/community/announcements --keep-alive
//   node tools/agent/scaffold-page.mjs --name foo --dry-run

import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const args = process.argv.slice(2);
function arg(name, fallback = '') {
  const index = args.indexOf('--' + name);
  if (index < 0) return fallback;
  const next = args[index + 1];
  return next && !next.startsWith('--') ? next : true;
}
const name = String(arg('name', '')).trim();
if (!name) {
  console.error('缺少 --name，例如：--name announcements');
  process.exit(1);
}
const title = String(arg('title', name));
const route = String(arg('route', '/' + name));
const panel = arg('panel', '');
const keepAlive = Boolean(arg('keep-alive', false));
const dryRun = Boolean(arg('dry-run', false));
const force = Boolean(arg('force', false));

const pascal = name.replace(/(^|[-_])([a-z])/g, (_, __, c) => c.toUpperCase());
const component = pascal + 'Page';
const pageDir = path.join(root, 'web-src/src/pages', name);
const pageFile = path.join(root, 'web-src/src/pages', component + '.vue');
const modelFile = path.join(pageDir, 'model.js');
const testFile = path.join(pageDir, 'model.test.js');
const taskFile = path.join(root, 'docs/agent/tasks', 'migrate-' + name + '.md');

const pageTemplate = `<template>
  <TabShell name="${name}">
    <section v-if="!connected" class="panel">
      <h3>连接管理端</h3>
      <p class="muted small">需要管理端 token；token 只保存在当前标签页的 sessionStorage。</p>
      <div class="inline-form">
        <el-input v-model="tokenInput" type="password" placeholder="Bearer token" show-password />
        <el-button type="primary" :loading="connecting" @click="connect">连接</el-button>
      </div>
      <p v-if="error" class="container-message error">{{ error }}</p>
    </section>

    <section v-else class="panel">
      <div class="page-toolbar">
        <strong>${title}</strong>
        <span class="muted small">{{ summaryText }}</span>
        <span class="spacer"></span>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
      <p v-if="error" class="container-message error">{{ error }}</p>
      <p v-if="loading" class="muted">读取中…</p>
      <p v-else-if="!model.items.length" class="muted">暂无数据。</p>
      <ul v-else class="plain-list">
        <li v-for="item in model.items" :key="item.id || item.name">
          {{ item.name || item.id }}
        </li>
      </ul>
    </section>
  </TabShell>
</template>

<script setup>
import { computed, reactive, ref } from 'vue';
import TabShell from '../components/TabShell.vue';
import { useApi } from '../composables/useApi.js';
import { useAuth } from '../composables/useAuth.js';
import { emptyModel, summaryText as formatSummary } from './${name}/model.js';

defineOptions({ name: '${component}' });

const { request } = useApi();
const model = reactive(emptyModel());
const loading = ref(false);
const message = ref('');

const {
  tokenInput,
  connected,
  connecting,
  error,
  connect,
} = useAuth({
  autoConnect: true,
  onConnected: load,
});

const summaryText = computed(() => formatSummary(model.items.length, false));

async function load() {
  loading.value = true;
  error.value = '';
  message.value = '';
  try {
    const result = await request('${route.replace(/\/$/, '')}');
    if (!result.ok) throw new Error(result.data?.error || '读取失败');
    model.items = Array.isArray(result.data.items) ? result.data.items : [];
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.inline-form {
  display: flex;
  gap: 8px;
  align-items: center;
  max-width: 520px;
}
.page-toolbar {
  display: flex;
  gap: 10px;
  align-items: baseline;
  margin-bottom: 12px;
}
.spacer { flex: 1; }
.plain-list { margin: 0; padding-left: 18px; }
</style>
`;

const modelTemplate = `/** 纯逻辑：与 DOM 无关，便于单测和后续 Agent 改造。 */
export function emptyModel() {
  return { items: [] };
}

export function summaryText(count, dirty) {
  const base = (Number(count) || 0) + ' 条记录';
  return dirty ? base + ' · 有未保存修改' : base;
}
`;

const testTemplate = `import { describe, expect, it } from 'vitest';
import { emptyModel, summaryText } from './model.js';

describe('${name} model', () => {
  it('空模型', () => {
    expect(emptyModel()).toEqual({ items: [] });
  });

  it('汇总文案', () => {
    expect(summaryText(2, false)).toBe('2 条记录');
    expect(summaryText(2, true)).toBe('2 条记录 · 有未保存修改');
  });
});
`;

const taskTemplate = fs.existsSync(path.join(root, 'docs/agent/tasks/_template.md'))
  ? fs.readFileSync(path.join(root, 'docs/agent/tasks/_template.md'), 'utf8')
      .replaceAll('<page>', name)
      .replaceAll('<XxxPage>', component)
      .replaceAll('<legacy script>', name + '.js')
      .replaceAll('<route>', route)
      .replaceAll('<api>', '/api/' + name)
      .replaceAll('<METHOD>', 'GET')
      .replaceAll('<page>', name)
      .replaceAll('<list>', '列表')
      .replaceAll('<editor fields>', '编辑字段')
      .replaceAll('<preview/send/save>', '预览/保存')
      .replaceAll('<yes|no>', keepAlive ? 'yes' : 'no')
      .replaceAll('<legacy>', name)
  : `# Task: migrate ${name}\n\n- route: ${route}\n- component: ${component}\n`;

function write(file, content) {
  if (dryRun) {
    console.log('[dry-run] ' + path.relative(root, file));
    return;
  }
  if (fs.existsSync(file) && !force) {
    console.log('skip existing ' + path.relative(root, file) + '（用 --force 覆盖）');
    return;
  }
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content, 'utf8');
  console.log('wrote ' + path.relative(root, file));
}

write(pageFile, pageTemplate);
write(modelFile, modelTemplate);
write(testFile, testTemplate);
write(taskFile, taskTemplate);

const meta = panel
  ? `{ workspace: 'samsara', section: '${name}', page: '${name}', title: '${title}', panel: '${panel}' }`
  : `{ workspace: 'samsara', section: '${name}', page: '${name}', title: '${title}' }`;
console.log('\n集成片段：');
console.log(`router.js: { path: 'samsara/${name}', component: ${component}, meta: ${meta} }`);
console.log(`navigation.js: SECTIONS 增加 { id: '${name}', label: '${title}', target: '${route}' }`);
if (keepAlive) console.log(`navigation.js: KEEP_ALIVE_PAGES 增加 '${component}'`);
console.log(`WebSecurity.java: 静态白名单增加 '${route}'`);
console.log(`legacy/admin.js: 删除 import '.../${name}.js'`);
console.log(`\n验证：cd web-src && npm.cmd run check && node tools/e2e/run.mjs --section ${name}`);
