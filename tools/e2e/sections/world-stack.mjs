function authHeaders(token) {
  return { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };
}

async function api(base, token, path, options = {}) {
  const response = await fetch(base + path, {
    ...options,
    headers: { ...authHeaders(token), ...(options.headers || {}) },
  });
  let body = null;
  try { body = await response.json(); } catch { body = null; }
  return { status: response.status, body };
}

const PNG_1X1 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk'
  + '+A8AAQUBAScY42YAAAAASUVORK5CYII=';

export default {
  name: 'world-stack',
  description: '世界栈：卡片图标区、时间线模态、归档预览与图标上传/回退',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep, base, token } = ctx;
    let uploadTarget = null;

    try {
      await navigate('/samsara/world/stack');
      await until("document.getElementById('panel-world-stack') && window.SamsaraWorldStack",
          20000, '世界栈页');
      await sleep(300);
      await evaluate('window.SamsaraWorldStack.load()');
      await until("document.querySelectorAll('#world-stack-list .world-stack-card').length >= 1",
          20000, '世界栈卡片');

      const layout = await evaluate(`(() => {
        const cards = [...document.querySelectorAll('#world-stack-list .world-stack-card')];
        const visuals = [...document.querySelectorAll('#world-stack-list .world-stack-card-visual')];
        const frames = [...document.querySelectorAll('#world-stack-list .world-stack-icon-frame')];
        const rect = frames[0]?.getBoundingClientRect();
        const badge = document.querySelector('#world-stack-list .world-stack-icon-badge');
        return {
          cards: cards.length,
          visuals: visuals.length,
          frames: frames.length,
          width: rect ? Math.round(rect.width) : 0,
          height: rect ? Math.round(rect.height) : 0,
          badge: badge?.textContent || '',
          source: badge?.dataset.source || '',
          overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth + 2,
        };
      })()`);
      check('每张世界栈卡片都有 16:9 图标视觉区',
          layout.cards >= 1 && layout.visuals === layout.cards && layout.frames === layout.cards
          && layout.width >= 120 && layout.height > 0
          && Math.abs(layout.width / layout.height - 16 / 9) < 0.2,
          JSON.stringify(layout));
      check('图标回退标识存在且页面不横向溢出',
          !layout.overflow
          && ['map', 'save', 'upload', 'placeholder'].includes(layout.source)
          && layout.badge.length > 0,
          JSON.stringify(layout));

      const timeline = await evaluate(`(async () => {
        const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
        const open = document.getElementById('world-stack-timeline-open');
        open?.click();
        await sleep(1200);
        const dialog = document.getElementById('world-stack-timeline-dialog');
        const result = {
          button: Boolean(open),
          dialog: Boolean(dialog),
          open: dialog?.open === true,
          nodes: document.querySelectorAll('#world-stack-timeline .timeline-node').length,
          nav: document.querySelector('#global-nav .nav-title')?.textContent.trim() || '',
        };
        dialog?.close();
        return result;
      })()`);
      check('世界栈时间线按钮打开模态浮窗',
          timeline.button && timeline.dialog && timeline.open && timeline.nav.startsWith('世界栈'),
          JSON.stringify(timeline));

      const archive = await evaluate(`(async () => {
        const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
        const card = [...document.querySelectorAll('#world-stack-list .world-stack-card')]
          .find((node) => !node.classList.contains('top'));
        const button = [...(card?.querySelectorAll('button') || [])]
          .find((node) => node.textContent.trim().startsWith('归档'));
        button?.click();
        await sleep(900);
        const dialog = document.getElementById('world-stack-archive-dialog');
        const preview = document.getElementById('world-stack-archive-preview');
        const result = {
          hasCard: Boolean(card),
          button: Boolean(button),
          dialog: Boolean(dialog),
          open: dialog?.open === true,
          previewTitle: preview?.querySelector('.world-stack-preview-title')?.textContent || '',
          legacyPanel: Boolean(document.getElementById('world-stack-archive-panel')),
          legacyList: Boolean(document.getElementById('world-stack-archive-list')),
        };
        dialog?.close();
        return result;
      })()`);
      if (archive.hasCard) {
        check('归档入口只保留卡片按钮并打开模态预览',
            archive.button && archive.dialog && archive.open
            && archive.previewTitle.startsWith('归档预览')
            && !archive.legacyPanel && !archive.legacyList,
            JSON.stringify(archive));
      } else {
        check('归档入口只保留卡片按钮并打开模态预览', true,
            '没有非栈顶卡片，跳过归档断言');
      }

      const model = await api(base, token, '/api/world-stack');
      uploadTarget = (model.body?.stacks || []).find((stack) => stack.id === 'world')
        || (model.body?.stacks || [])[0] || null;
      if (!uploadTarget || (uploadTarget.icon && uploadTarget.icon.custom)) {
        check('自定义图标上传/回退', true, '已存在自定义图标，跳过上传以保护测试数据');
      } else {
        const uploaded = await api(base, token, '/api/world-stack/icon', {
          method: 'POST',
          body: JSON.stringify({ id: uploadTarget.id, mime: 'image/png', data: PNG_1X1 }),
        });
        check('自定义图标上传成功',
            uploaded.status === 200 && uploaded.body?.ok === true
            && uploaded.body?.icon?.source === 'upload',
            JSON.stringify(uploaded.body));
        const uploadedView = await evaluate(`(async () => {
          await window.SamsaraWorldStack.load();
          await new Promise((resolve) => setTimeout(resolve, 700));
          const badge = document.querySelector('#world-stack-list .world-stack-icon-badge');
          return { source: badge?.dataset.source || '', text: badge?.textContent || '' };
        })()`);
        check('上传后卡片显示自定义图标',
            uploadedView.source === 'upload' && uploadedView.text.includes('自定义'),
            JSON.stringify(uploadedView));
        const deleted = await api(base, token, '/api/world-stack/icon/delete', {
          method: 'POST',
          body: JSON.stringify({ id: uploadTarget.id }),
        });
        check('删除自定义图标后回到回退图', deleted.status === 200
            && deleted.body?.ok === true && deleted.body?.icon?.source !== 'upload',
            JSON.stringify(deleted.body));
        const deletedView = await evaluate(`(async () => {
          await window.SamsaraWorldStack.load();
          await new Promise((resolve) => setTimeout(resolve, 700));
          const badge = document.querySelector('#world-stack-list .world-stack-icon-badge');
          return { source: badge?.dataset.source || '', text: badge?.textContent || '' };
        })()`);
        check('删除后卡片恢复回退标识',
            deletedView.source !== 'upload' && !deletedView.text.includes('自定义'),
            JSON.stringify(deletedView));
        uploadTarget = null;
      }

      const errors = await evaluate('window.__e2eErrors || []');
      check('世界栈页没有未捕获脚本错误', errors.length === 0, errors.slice(0, 3).join(' | '));
    } finally {
      if (uploadTarget) {
        // 中途失败时尽量恢复默认图标，避免污染测试服。
        await api(base, token, '/api/world-stack/icon/delete', {
          method: 'POST',
          body: JSON.stringify({ id: uploadTarget.id }),
        }).catch(() => {});
      }
    }
  },
};
