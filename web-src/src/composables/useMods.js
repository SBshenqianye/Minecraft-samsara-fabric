import { ref } from 'vue';
import { useApi } from './useApi.js';

function hasCjk(text) {
  return /[\u3400-\u9fff]/.test(String(text || ''));
}

/**
 * “其他模组”列表：从 /api/config/pages 的第三方 PageSpec 归组。
 * 未连接时静默重试，避免首次进入管理端时接口尚未就绪。
 */
export function useMods() {
  const { request } = useApi();
  const mods = ref([]);
  let retry = 0;

  async function loadMods() {
    try {
      const result = await request('/api/config/pages');
      if (!result.ok) return;
      const data = result.data || {};
      const names = data.modNames || {};
      const groups = new Map();
      for (const pageSpec of data.pages || []) {
        if (pageSpec.source !== 'third-party') continue;
        const relative = String(pageSpec.id || '').startsWith('third-party:')
          ? String(pageSpec.id).substring('third-party:'.length)
          : String(pageSpec.fileName || '');
        const normalized = relative.replace(/\\/g, '/');
        const first = normalized.split('/')[0];
        if (!first) continue;
        if (!groups.has(first)) groups.set(first, []);
        groups.get(first).push(pageSpec);
      }
      mods.value = [...groups.entries()]
        .map(([id, pages]) => {
          const name = String(names[id] || names[id.toLowerCase()] || '').trim();
          const chinese = hasCjk(name) ? name : '';
          return {
            id,
            pages,
            label: chinese || id,
            hint: chinese ? id : pages.length + ' 个配置文件',
          };
        })
        .sort((a, b) => a.id.localeCompare(b.id));
      retry = 0;
    } catch {
      // 未连接或接口不可用时保持空列表；连接完成后自动重试。
      if (retry++ < 20) window.setTimeout(loadMods, 600);
    }
  }

  return { mods, loadMods };
}
