export default {
  name: 'storage',
  description: '只读存储布局：路径、大小、文件数、随周目/备份标记',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    await navigate('/samsara/system/storage');
    // SPA 首帧先渲染壳层，等一拍再断言页面内容，避免导航事件与路由渲染竞争。
    await sleep(1200);
    await until("document.querySelector('[data-page=\"storage-layout\"]')", 20000, '存储布局页');
    await until(
      "document.querySelectorAll('#storage-layout-rows .storage-row').length >= 4",
      20000,
      '存储布局行',
    );
    await sleep(200);

    const state = await evaluate(`(() => {
      const rows = [...document.querySelectorAll('#storage-layout-rows .storage-row')];
      return {
        page: !!document.querySelector('[data-page="storage-layout"]'),
        rows: rows.length,
        ids: rows.map((row) => row.querySelector('.mono')?.textContent.trim() || ''),
        texts: rows.map((row) => row.textContent).join(' '),
        navItems: [...document.querySelectorAll('#page-nav .nav-item')].map((n) => n.textContent.trim()),
        title: document.querySelector('.page-hero-title')?.textContent.trim() || '',
        message: document.getElementById('storage-layout-message')?.textContent.trim() || '',
        errors: window.__e2eErrors || [],
      };
    })()`);

    check('存储布局显示六类目录',
      state.page && state.rows >= 6
      && ['config', 'world', 'shared', 'identity', 'global', 'backups']
        .every((id) => state.ids.includes(id)),
      JSON.stringify({ rows: state.rows, ids: state.ids }));
    check('存储布局标注随周目/备份与大小',
      state.texts.includes('随周目') && state.texts.includes('进本体备份')
      && /个文件/.test(state.texts),
      JSON.stringify({ message: state.message }));
    check('存储布局页面标题正确',
      state.title.includes('存储布局'), JSON.stringify(state.title));
    check('存储布局页没有未捕获脚本错误',
      state.errors.length === 0, state.errors.slice(0, 3).join(' | '));

    await sleep(100);
  },
};
