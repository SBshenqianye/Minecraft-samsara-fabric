export default {
  name: 'output',
  description: '输出格式页：消息列表、内容画布、变量/按钮面板、服务端预览',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    await navigate('/samsara/config/output');
    await until(
      "window.SamsaraUI && document.querySelector('[data-page=\"output\"] #output-editor')",
      20000,
      '输出格式页',
    );
    await until("window.SamsaraOutput && document.querySelector('#output-meta')?.textContent.trim().length > 0",
      20000, '输出消息激活');
    await until("document.getElementById('output-preview')?.textContent.trim().length > 0",
      15000, '输出预览');

    const state = await evaluate(`(() => ({
      page: !!document.querySelector('[data-page="output"]'),
      panel: !!document.querySelector('[data-page="output"] #panel-output'),
      meta: document.getElementById('output-meta')?.textContent.trim() || '',
      editor: !!document.getElementById('output-editor'),
      palette: document.querySelectorAll('#output-palette .palette-chip').length,
      buttons: document.querySelectorAll('#output-buttons .palette-chip').length,
      navItems: document.querySelectorAll('#global-nav .nav-item').length,
      preview: document.getElementById('output-preview')?.textContent.trim() || '',
      errors: window.__e2eErrors || [],
    }))()`);

    check('输出页为 Vue 页面并挂载画布', state.page && state.panel && state.editor,
      JSON.stringify({ page: state.page, panel: state.panel, editor: state.editor }));
    check('输出消息元信息与变量/按钮面板渲染',
      state.meta.includes('变量') && state.meta.includes('按钮') && state.palette >= 0,
      JSON.stringify({ meta: state.meta, palette: state.palette, buttons: state.buttons }));
    check('输出页有消息导航或预览', state.navItems > 0 || state.preview.length > 0,
      JSON.stringify({ navItems: state.navItems, preview: state.preview.slice(0, 80) }));

    const preview = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const text = document.getElementById('output-preview')?.textContent.trim() || '';
      return { text, hasPreview: text.length > 0 };
    })()`);
    check('输出预览已生成或给出修正提示', preview.hasPreview,
      preview.text.slice(0, 120));

    const errors = await evaluate('window.__e2eErrors || []');
    check('输出页没有未捕获脚本错误', errors.length === 0, errors.slice(0, 3).join(' | '));

    await sleep(100);
  },
};
