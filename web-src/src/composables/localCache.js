// 浏览器本地缓存：用于天网首次打开/强制刷新时先渲染上次数据，再后台刷新。
// 只缓存 JSON 可序列化的小数据；写入失败（隐私模式/配额满）时静默忽略。

export function readCache(key, maxAgeMs = 0) {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return null;
    if (maxAgeMs > 0 && Date.now() - Number(parsed.ts || 0) > maxAgeMs) return null;
    return parsed.data ?? null;
  } catch {
    return null;
  }
}

export function writeCache(key, data) {
  try {
    localStorage.setItem(key, JSON.stringify({ ts: Date.now(), data }));
  } catch {
    // 配额满或隐私模式：忽略缓存，不影响页面。
  }
}

export function removeCache(key) {
  try {
    localStorage.removeItem(key);
  } catch {
    // ignore
  }
}
