export default {
  name: 'server',
  description: '本体页四面板：配置（分组/搜索/恢复默认/MOTD 色板）、备份时间线、任务、文件位置、Web 审计预览',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    // ---- 服务器配置 ----
    await navigate('/vanilla/config');
    await until("document.querySelectorAll('#server-config-files > details.file-block').length >= 6",
      25000, '本体配置文件');
    await sleep(300);

    const config = await evaluate(`(() => {
      const files = [...document.querySelectorAll('#server-config-files > details.file-block')];
      return {
        files: files.length,
        fileIds: files.map((node) => node.id),
        groups: document.querySelectorAll('#server-config-files .group-block').length,
        entries: document.querySelectorAll('#server-config-files .entry').length,
        segmented: document.querySelectorAll('#server-config-files .entry .segmented').length,
        missingDesc: [...document.querySelectorAll('#server-config-files .entry')]
          .filter((entry) => !entry.querySelector('.entry-desc')?.textContent.trim()).length,
        saveLabel: document.getElementById('server-save')?.textContent.trim() || '',
        navTitle: document.querySelector('#page-nav .nav-title')?.textContent.trim() || '',
        legacyPanel: Boolean(document.getElementById('panel-server')),
      };
    })()`);
    check('本体配置页为原生 Vue：6 个文件同页且无旧 panel',
      config.files === 6 && config.fileIds.includes('server-file-server-properties')
      && config.groups >= 4 && config.entries >= 70 && config.segmented >= 20
      && config.missingDesc === 0 && !config.legacyPanel,
      JSON.stringify(config));
    check('本体配置页内导航与保存按钮',
      config.navTitle.startsWith('服务器配置') && config.saveLabel === '保存并重载',
      JSON.stringify({ nav: config.navTitle, save: config.saveLabel }));

    const motd = await evaluate(`(() => {
      const entry = [...document.querySelectorAll('#server-config-files .entry')]
        .find((node) => node.querySelector('.entry-key')?.textContent.includes('motd'));
      const editor = entry?.querySelector('.chat-text-editor');
      const cells = editor ? [...editor.querySelectorAll('.chat-text-color-grid .claim-color-cell')] : [];
      const input = editor?.querySelector('.chat-text-input');
      const before = input?.value || '';
      cells[10]?.click();
      const after = input?.value || '';
      if (input) {
        input.value = before;
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }
      return {
        editor: Boolean(editor),
        cells: cells.length,
        inserted: after !== before && after.includes('§'),
        serverList: Boolean(editor?.querySelector('.server-list-card')),
        lines: editor?.querySelectorAll('.server-list-line').length || 0,
        spans: editor?.querySelectorAll('.chat-text-rendered span').length || 0,
        togglePressed: Boolean(editor?.querySelector('.chat-text-toggle[aria-pressed]')),
        saveAfter: document.getElementById('server-save')?.textContent.trim() || '',
      };
    })()`);
    check('MOTD 4×4 色板可插入并渲染服务器列表预览',
      motd.editor && motd.cells === 16 && motd.inserted && motd.serverList
      && motd.lines === 2 && motd.spans > 0 && !motd.togglePressed,
      JSON.stringify(motd));
    check('MOTD 草稿改回原值后不产生假修改', motd.saveAfter === '保存并重载', motd.saveAfter);

    const search = await evaluate(`(async () => {
      const input = document.getElementById('server-config-search');
      input.value = 'motd';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((resolve) => setTimeout(resolve, 250));
      const visible = document.querySelectorAll('#server-config-files > details.file-block').length;
      const keys = [...document.querySelectorAll('#server-config-files .entry-key')]
        .map((node) => node.textContent.trim());
      input.value = '';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((resolve) => setTimeout(resolve, 250));
      return { visible, keys, restored: document.querySelectorAll('#server-config-files > details.file-block').length };
    })()`);
    check('配置搜索只显示匹配文件与条目',
      search.visible === 1 && search.keys.length === 1 && search.restored === 6,
      JSON.stringify(search));

    const reset = await evaluate(`(async () => {
      const find = (key) => [...document.querySelectorAll('#server-config-files .entry')]
        .find((entry) => (entry.querySelector('.entry-key')?.textContent || '')
          .trim().split(/\\s+/)[0] === key);
      const resetOf = (entry) => [...(entry?.querySelectorAll('.entry-actions button') || [])]
        .find((button) => button.textContent.trim() === '恢复默认');
      const save = document.getElementById('server-save');
      const before = find('pause-when-empty-seconds')?.querySelector('input')?.value || '';
      resetOf(find('pause-when-empty-seconds'))?.click();
      await new Promise((resolve) => setTimeout(resolve, 200));
      const after = find('pause-when-empty-seconds')?.querySelector('input')?.value || '';
      const label = save.textContent.trim();
      document.getElementById('server-reload')?.click();
      await new Promise((resolve) => setTimeout(resolve, 800));
      return { before, after, label, restored: save.textContent.trim() };
    })()`);
    check('恢复默认使用原版默认值并标记未保存',
      reset.after === '60' && reset.after !== reset.before && reset.label.includes('保存并重载（'),
      JSON.stringify(reset));
    check('重新读取清空未保存修改', reset.restored === '保存并重载', reset.restored);

    const picker = await evaluate(`(async () => {
      const file = document.getElementById('server-file-whitelist');
      const button = [...file.querySelectorAll('button')]
        .find((node) => node.textContent.trim() === '从玩家选择');
      button?.click();
      await new Promise((resolve) => setTimeout(resolve, 600));
      const dialog = document.getElementById('player-dialog');
      const result = {
        button: Boolean(button),
        open: dialog?.open === true,
        rows: document.querySelectorAll('#player-list .player-row').length,
      };
      document.getElementById('player-cancel')?.click();
      await new Promise((resolve) => setTimeout(resolve, 150));
      result.closed = dialog?.open === false;
      return result;
    })()`);
    check('玩家选择弹窗原生可开关', picker.button && picker.open && picker.closed,
      JSON.stringify(picker));

    // ---- 存档备份 ----
    await navigate('/vanilla/backup');
    await until("document.getElementById('server-backup-panel')"
      + " && !document.getElementById('server-backup-panel').hidden", 20000, '存档备份面板');
    await sleep(1200);

    const backup = await evaluate(`(() => ({
      stats: document.querySelectorAll('#server-backup-stats .stat').length,
      nodes: document.querySelectorAll('#server-backup-timeline .timeline-node').length,
      current: document.querySelectorAll('#server-backup-timeline .timeline-node.current').length,
      jobs: document.getElementById('server-job-list')?.textContent.includes('任务') === false,
      jobCards: document.querySelectorAll('#server-job-list .server-job').length,
      files: document.querySelectorAll('#world-stack-files .world-stack-file').length,
      filePanel: Boolean(document.getElementById('world-stack-files-panel')),
      navTitle: document.querySelector('#page-nav .nav-title')?.textContent.trim() || '',
      create: Boolean(document.getElementById('server-backup-create')),
    }))()`);
    check('备份面板统计/时间线/文件位置齐全',
      backup.stats === 6 && backup.files >= 1 && backup.filePanel && backup.create
      && backup.navTitle.startsWith('存档备份'), JSON.stringify(backup));
    if (backup.nodes > 0) {
      check('备份时间线只有一个当前节点', backup.current === 1,
        JSON.stringify({ nodes: backup.nodes, current: backup.current }));
    } else {
      check('备份时间线只有一个当前节点', true, '测试服没有本体备份，跳过');
    }

    const restorePreview = await evaluate(`(async () => {
      const button = [...document.querySelectorAll('#server-backup-timeline button')]
        .find((node) => node.textContent.trim() === '回滚到这里');
      if (!button) return { skipped: true };
      button.click();
      await new Promise((resolve) => setTimeout(resolve, 700));
      const box = document.getElementById('server-backup-preview');
      const result = {
        skipped: false,
        hidden: box?.hidden === true,
        title: box?.querySelector('.world-stack-preview-title')?.textContent || '',
        rows: box?.querySelectorAll('.world-stack-rows dt').length || 0,
      };
      if (box) box.hidden = true;
      return result;
    })()`);
    check('备份恢复预览在 Vue 中打开',
      restorePreview.skipped || (!restorePreview.hidden && restorePreview.title.startsWith('恢复预览')
        && restorePreview.rows >= 5), JSON.stringify(restorePreview));

    const createPreview = await evaluate(`(async () => {
      document.getElementById('server-backup-create')?.click();
      await new Promise((resolve) => setTimeout(resolve, 900));
      const box = document.getElementById('server-backup-preview');
      const result = {
        hidden: box?.hidden === true,
        title: box?.querySelector('.world-stack-preview-title')?.textContent || '',
        rows: box?.querySelectorAll('.world-stack-rows dt').length || 0,
      };
      if (box) box.hidden = true;
      return result;
    })()`);
    check('创建备份预览只读展示空间与保留策略',
      !createPreview.hidden && createPreview.title.startsWith('创建备份预览')
      && createPreview.rows >= 5, JSON.stringify(createPreview));

    // ---- Web 审计 ----
    await navigate('/samsara/system/audit');
    await until("document.getElementById('server-audit-panel')"
      + " && !document.getElementById('server-audit-panel').hidden", 20000, 'Web 审计面板');
    await sleep(1500);

    const audit = await evaluate(`(async () => {
      const rows = [...document.querySelectorAll('#server-audit-list .server-audit-item')];
      const row = rows.find((item) => item.textContent.includes('可回滚')) || rows[0];
      const result = {
        rows: rows.length,
        navTitle: document.querySelector('#page-nav .nav-title')?.textContent.trim() || '',
        message: document.getElementById('server-audit-message')?.textContent || '',
        previewButton: Boolean(row?.querySelector('button')),
        title: '',
        diff: '',
        visible: false,
        changes: 0,
      };
      row?.querySelector('button')?.click();
      await new Promise((resolve) => setTimeout(resolve, 900));
      result.title = document.getElementById('server-audit-diff-title')?.textContent || '';
      result.diff = document.getElementById('server-audit-diff')?.textContent
        || document.getElementById('server-audit-diffs')?.textContent || '';
      result.visible = document.getElementById('server-audit-diff-wrap')?.open === true;
      result.changes = document.querySelectorAll('#server-audit-changes .server-audit-change').length;
      document.getElementById('server-audit-diff-close')?.click();
      await new Promise((resolve) => setTimeout(resolve, 200));
      result.closed = !document.getElementById('server-audit-diff-wrap');
      return result;
    })()`);
    check('Web 审计列表与页内导航', audit.rows >= 1 && audit.navTitle.startsWith('Web 审计')
      && audit.message.includes('审计记录'), JSON.stringify({ rows: audit.rows, nav: audit.navTitle }));
    check('Web 审计预览打开文件级 diff',
      audit.previewButton && audit.visible && audit.title.startsWith('差异 ·')
      && (audit.diff.includes('---') || audit.diff.includes('没有可显示')) && audit.closed === true,
      JSON.stringify({ title: audit.title, visible: audit.visible, closed: audit.closed,
        diff: audit.diff.slice(0, 120) }));

    const errors = await evaluate('window.__e2eErrors || []');
    check('本体四面板没有未捕获脚本错误', errors.length === 0, errors.slice(0, 3).join(' | '));

    await sleep(100);
  },
};
