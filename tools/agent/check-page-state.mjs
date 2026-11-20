import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 顶层页面状态守卫：错误块只能来自 StateBlock / PageState。
 *
 * 背景：页面曾各自手写 `<p class="container-message error">`，导致同一种读取失败
 * 有三种呈现（灰文案 / 红 alert / 全局消息），E2E 也无法统一断言。
 * 允许的写法：
 *   - 区块错误 → <StateBlock :error="..." />
 *   - 整页错误 → <PageState :error="..." />
 *   - 有意用顶部全局提示的页面（如 ServerPage/LotteryPage 的 message banner）不加该类名即可。
 *
 * 用法：node tools/agent/check-page-state.mjs（可在仓库根或 web-src 下运行）
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');

const router = fs.readFileSync(path.join(root, 'web-src/src/router.js'), 'utf8');
const pages = [...new Set([...router.matchAll(/import\('\.\/pages\/([\w/]+)\.vue'\)/g)]
  .map((m) => 'web-src/src/pages/' + m[1] + '.vue'))];

const ADHOC_ERROR = /class="container-message error"/;
const problems = [];
for (const file of pages) {
  const src = fs.readFileSync(path.join(root, file), 'utf8');
  if (ADHOC_ERROR.test(src)) {
    problems.push(file + ' → 手写错误块，改用 <StateBlock :error="..."/> 或 <PageState :error="..."/>');
  }
}

if (problems.length) {
  console.error('PAGE_STATE_FAIL pages=' + pages.length + ' problems=' + problems.length);
  for (const item of problems) console.error('  - ' + item);
  console.error('约定见 docs/Web 页面开发约定.md「加载 / 错误 / 空态」。');
  process.exit(1);
}
console.log('PAGE_STATE_OK pages=' + pages.length);
