let documentPromise = null;

/**
 * 旧页面壳只拉取一次；所有 Tab 页面共享同一份解析后的 Document。
 * 每个页面用 importNode 复制自己的 panel，避免 innerHTML。
 */
export function loadLegacyDocument() {
  if (!documentPromise) {
    documentPromise = fetch('/legacy.html', { cache: 'no-store' })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error('旧页面壳加载失败：' + response.status);
        }
        const html = await response.text();
        return new DOMParser().parseFromString(html, 'text/html');
      })
      .catch((error) => {
        documentPromise = null;
        throw error;
      });
  }
  return documentPromise;
}

export async function importLegacyElement(id) {
  const doc = await loadLegacyDocument();
  const source = doc.getElementById(id);
  if (!source) {
    throw new Error('旧页面缺少元素：' + id);
  }
  return document.importNode(source, true);
}

export function waitForElement(id, timeoutMs = 4000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const tick = () => {
      const found = document.getElementById(id);
      if (found) {
        resolve(found);
        return;
      }
      if (Date.now() - started >= timeoutMs) {
        reject(new Error('等待页面元素超时：' + id));
        return;
      }
      window.setTimeout(tick, 40);
    };
    tick();
  });
}
