export default {
  name: 'config-editor',
  description: '第三方模组文件页复用 PageSpec 编辑器：文件夹树、文件 Tab、字段控件',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

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
