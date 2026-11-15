export default {
  name: 'config-editor',
  description: 'PageSpec 配置编辑器与第三方模组文件页：页面导航、字段控件、预览弹窗、文件夹树',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    await navigate('/samsara/config/editor');
    await until("document.querySelector('#config-editor-body .config-editor-page-head h3')",
      25000, '配置编辑器页面');
    await until("document.querySelectorAll('#config-editor-body .entry').length > 0",
      25000, '配置字段');
    await sleep(300);

    const editor = await evaluate(`(() => ({
      navTitle: document.querySelector('#page-nav .nav-title')?.textContent.trim() || '',
      navGroups: [...document.querySelectorAll('#page-nav .nav-item')]
        .map((node) => node.textContent.replace(/\\s+/g, ' ').trim()),
      fields: document.querySelectorAll('#config-editor-body .entry').length,
      groups: document.querySelectorAll('#config-editor-body .config-editor-group').length,
      title: document.querySelector('#config-editor-body h3')?.textContent || '',
      summary: document.getElementById('config-editor-summary')?.textContent || '',
      legacy: Boolean(document.getElementById('panel-config-editor-legacy')),
    }))()`);
    check('配置编辑器页面导航与字段渲染',
      editor.navTitle.startsWith('配置编辑器') && editor.navGroups.length >= 2
      && editor.fields > 0 && editor.groups >= 1 && editor.title.length > 0
      && editor.summary.includes('页面') && !editor.legacy, JSON.stringify(editor));

    const preview = await evaluate(`(async () => {
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const button = [...document.querySelectorAll('#config-editor-body button')]
        .find((node) => node.textContent.includes('预览'));
      button?.click();
      await wait(1200);
      const dialog = document.getElementById('config-editor-preview-dialog');
      const result = {
        button: Boolean(button),
        open: dialog?.open === true,
        sub: document.getElementById('config-editor-preview-sub')?.textContent || '',
        diff: document.getElementById('config-editor-preview-diff')?.textContent || '',
      };
      dialog?.close();
      return result;
    })()`);
    check('配置编辑器预览弹窗', preview.button && preview.open && preview.sub.includes('将修改')
      && preview.diff.length > 0, JSON.stringify(preview));

    await navigate('/mods/puppet-player-config.json');
    await until("document.querySelector('.mod-config-layout .mod-file-tab')", 25000, '模组文件页');
    await until("document.querySelectorAll('.mod-editor-host #config-editor-body .entry').length > 0",
      25000, '模组字段');
    const mod = await evaluate(`(() => ({
      tabs: [...document.querySelectorAll('.mod-file-tab')]
        .map((node) => node.textContent.trim()),
      fields: document.querySelectorAll('.mod-editor-host #config-editor-body .entry').length,
      tree: document.querySelectorAll('.mod-tree .folder-node, .mod-tree .file-node').length,
      navHidden: !document.getElementById('page-nav')
        || document.getElementById('page-nav').hidden === true,
    }))()`);
    check('第三方模组文件页复用配置编辑器', mod.tabs.length >= 1 && mod.fields > 0
      && mod.tree >= 1 && mod.navHidden === true, JSON.stringify(mod));

    const errors = await evaluate('window.__e2eErrors || []');
    check('配置编辑器没有未捕获脚本错误', errors.length === 0, errors.slice(0, 3).join(' | '));
    await sleep(100);
  },
};
