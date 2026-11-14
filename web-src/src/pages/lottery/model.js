/** 经济业务页的纯格式化逻辑。 */

export function vp(value) {
  const n = Number(value || 0);
  if (!Number.isFinite(n)) return '0 VP';
  const text = Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
  return text + ' VP';
}

export function dayText(id) {
  if (!id) return '—';
  if (id.startsWith('game-')) return '第 ' + id.slice(5) + ' 游戏日';
  return id;
}

export function timeText(ms) {
  if (!ms) return '—';
  try { return new Date(ms).toLocaleString(); } catch (error) { return String(ms); }
}

export function dateText(ms) {
  if (!ms) return '—';
  try { return new Date(ms).toLocaleDateString(); } catch (error) { return String(ms); }
}
