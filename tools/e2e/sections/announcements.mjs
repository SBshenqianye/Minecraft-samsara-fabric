export default {
  name: 'announcements',
  description: '原生公告页：列表、编辑器、预览、dirty 状态',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    await navigate('/samsara/community/announcements');
    await until(
      "window.SamsaraUI && document.querySelector('[data-page=\"announcements\"]')",
      20000,
      '原生公告页',
    );
    await until(
      "document.querySelectorAll('#announcements-list .announcement-item').length >= 1",
      20000,
      '公告列表',
    );

    const state = await evaluate(`(() => ({
      page: !!document.querySelector('[data-page="announcements"]'),
      legacyPanel: !!document.getElementById('panel-announcements'),
      items: document.querySelectorAll('#announcements-list .announcement-item').length,
      fields: document.querySelectorAll('#announcements-editor .announcement-row').length,
      summary: document.getElementById('announcements-summary')?.textContent.trim() || '',
      hasMessage: !!document.getElementById('announcements-message'),
      hasPreview: !!document.getElementById('announcement-preview'),
      hasAdd: !!document.getElementById('announcements-add'),
      hasRefresh: !!document.getElementById('announcements-refresh'),
      navHidden: document.getElementById('global-nav')?.hidden === true,
      noNav: document.querySelector('main.app-shell')?.classList.contains('no-nav') === true,
      errors: window.__e2eErrors || [],
    }))()`);

    check('公告页为原生 Vue 页面', state.page && !state.legacyPanel,
      JSON.stringify({ page: state.page, legacyPanel: state.legacyPanel }));
    check('公告列表与编辑器渲染', state.items >= 1 && state.fields >= 5,
      JSON.stringify({ items: state.items, fields: state.fields }));
    check('公告摘要与消息区存在', state.summary.includes('条公告') && state.hasMessage,
      JSON.stringify({ summary: state.summary, hasMessage: state.hasMessage }));
    check('公告页不显示全局左侧导航', state.navHidden && state.noNav,
      JSON.stringify({ navHidden: state.navHidden, noNav: state.noNav }));

    const preview = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const button = [...document.querySelectorAll('#announcements-editor button')]
        .find((node) => node.textContent.trim() === '预览');
      if (!button) return { found: false, text: '' };
      button.click();
      await sleep(1200);
      const box = document.getElementById('announcement-preview');
      return {
        found: true,
        text: box?.textContent || '',
        title: box?.querySelector('.announcement-preview-title')?.textContent || '',
        message: document.getElementById('announcements-message')?.textContent || '',
      };
    })()`);
    check('公告预览显示变量替换结果', preview.found
      && preview.title.includes('预览')
      && preview.text.includes('聊天'),
    JSON.stringify({ title: preview.title, text: preview.text.slice(0, 120) }));

    const dirty = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const input = document.querySelector('#announcements-editor input[type="text"]');
      if (!input) return { changed: false, dirty: false, clean: false };
      const before = input.value;
      input.value = before + ' E2E';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await sleep(150);
      const dirtyText = document.getElementById('announcements-summary')?.textContent || '';
      const refresh = document.getElementById('announcements-refresh');
      refresh?.click();
      await sleep(1200);
      const cleanText = document.getElementById('announcements-summary')?.textContent || '';
      return { changed: true, dirty: dirtyText.includes('未保存'), clean: !cleanText.includes('未保存') };
    })()`);
    check('公告编辑有未保存提示且刷新可恢复', dirty.changed && dirty.dirty && dirty.clean,
      JSON.stringify(dirty));

    const final = await evaluate(`(() => ({
      errors: window.__e2eErrors || [],
      message: document.getElementById('announcements-message')?.textContent || '',
    }))()`);
    check('公告页没有未捕获脚本错误', final.errors.length === 0,
      final.errors.slice(0, 3).join(' | '));
    check('公告页消息区可用', final.message.length >= 0, final.message.slice(0, 80));

    await sleep(100);
  },
};
