/** 本体服务器页的纯格式化与 properties 解析逻辑。 */

export function formatBytes(bytes) {
  const n = Number(bytes || 0);
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB';
  return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB';
}

export function formatTime(ms) {
  const n = Number(ms || 0);
  if (!n) return '—';
  try {
    return new Date(n).toLocaleString();
  } catch (e) {
    return String(n);
  }
}

/**
 * 把 properties 文本拆成可编辑行：
 * - 去掉 UTF-8 BOM
 * - `#` / `!` 开头的是注释，不能当成键值
 * - 第一个 `=` 之后全部是值
 */
export function splitPropertyLines(content) {
  return String(content || '').replace(/^\uFEFF/, '').split(/\r?\n/).map((line) => {
    if (line === '') return { type: 'blank', raw: '' };
    if (/^\s*[#!]/.test(line) || /^\s*\\[#!]/.test(line)) {
      return { type: 'comment', raw: line };
    }
    const eq = line.indexOf('=');
    if (eq <= 0) return { type: 'raw', raw: line };
    const value = line.slice(eq + 1);
    return { type: 'prop', key: line.slice(0, eq), value, originalValue: value };
  });
}
