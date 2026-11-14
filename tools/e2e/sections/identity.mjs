export default {
  name: 'identity',
  description: '身份组：Vue 设置卡片、组卡片/池子、data UUID 详情弹窗与内嵌背包',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    await navigate('/samsara/community/identity');
    await until("document.getElementById('panel-identity')"
      + " && document.querySelectorAll('#panel-identity .identity-card').length >= 1",
      25000, '身份组卡片');
    await until("document.querySelector('#panel-identity .uuid-chip.data')", 25000, 'data UUID chip');
    await sleep(400);

    const settings = await evaluate(`(() => ({
      enabled: document.querySelectorAll('#identity-enabled .segmented-btn').length,
      sync: document.querySelectorAll('#identity-sync .segmented-btn').length,
      maxOwners: document.getElementById('identity-max-owners')?.value || '',
      path: document.getElementById('identity-path')?.textContent || '',
      root: document.getElementById('identity-root')?.textContent || '',
      cards: document.querySelectorAll('#panel-identity .identity-card').length,
      premium: document.querySelectorAll('#identity-premium-pool .uuid-chip').length,
      offline: document.querySelectorAll('#identity-offline-pool .uuid-chip').length,
      data: document.querySelectorAll('#identity-data-pool .uuid-chip').length,
      sections: [...document.querySelectorAll('#panel-identity .pool-section > summary')]
        .map((node) => node.textContent.replace(/\\s+/g, ' ').trim()),
    }))()`);
    check('身份组设置为原生分段按钮',
      settings.enabled === 2 && settings.sync === 2 && Number(settings.maxOwners) >= 1
      && settings.path.startsWith('配置文件：') && settings.root.startsWith('运行时数据：'),
      JSON.stringify(settings));
    check('组卡片与账号/data 池子齐全',
      settings.cards >= 1 && settings.sections.length === 3
      && settings.premium + settings.offline + settings.data >= 1,
      JSON.stringify({ cards: settings.cards, sections: settings.sections }));

    const toggle = await evaluate(`(async () => {
      const host = document.getElementById('identity-enabled');
      const active = host.querySelector('.segmented-btn.active')?.dataset.value || '';
      const other = [...host.querySelectorAll('.segmented-btn')]
        .find((button) => button.dataset.value !== active);
      other?.click();
      await new Promise((resolve) => setTimeout(resolve, 250));
      const next = host.querySelector('.segmented-btn.active')?.dataset.value || '';
      const save = document.getElementById('save')?.textContent.trim() || '';
      return { active, next, save };
    })()`);
    check('切换启用状态走 Vue 并标记未保存',
      toggle.active && toggle.next && toggle.active !== toggle.next,
      JSON.stringify(toggle));

    // 还原，避免污染测试服配置。
    await evaluate(`(async () => {
      const host = document.getElementById('identity-enabled');
      const before = ${JSON.stringify(toggle.active)};
      const button = host.querySelector('.segmented-btn[data-value="' + before + '"]');
      button?.click();
      await new Promise((resolve) => setTimeout(resolve, 150));
      return true;
    })()`);

    const detail = await evaluate(`(async () => {
      const chip = document.querySelector('#panel-identity .uuid-chip.data');
      const action = chip ? [...chip.querySelectorAll('.chip-action')]
        .find((node) => node.textContent.trim() === '详情') : null;
      action?.click();
      for (let i = 0; i < 40; i += 1) {
        if (document.querySelector('#identity-data-content .identity-data-head')) break;
        await new Promise((resolve) => setTimeout(resolve, 200));
      }
      await new Promise((resolve) => setTimeout(resolve, 400));
      const content = document.getElementById('identity-data-content');
      const frame = content?.querySelector('iframe');
      const result = {
        action: Boolean(action),
        dialog: Boolean(document.querySelector('.samsara-framework-dialog')),
        copy: [...(content?.querySelectorAll('button') || [])]
          .some((node) => node.textContent.includes('复制 UUID')),
        rename: [...(content?.querySelectorAll('button') || [])]
          .some((node) => node.textContent.includes('命名/备注')),
        iframe: Boolean(frame),
        statTabs: [...(content?.querySelectorAll('.identity-stat-tabs:not(.identity-adv-tabs) button') || [])]
          .map((node) => node.textContent.trim()),
        advTabs: [...(content?.querySelectorAll('.identity-adv-tabs button') || [])]
          .map((node) => node.textContent.trim()),
        hasBackupList: (content?.textContent || '').includes('备份记录'),
      };
      window.SamsaraDialog?.close('identity-data-dialog');
      await new Promise((resolve) => setTimeout(resolve, 200));
      return result;
    })()`);
    check('data UUID 详情弹窗完整',
      detail.action && detail.dialog && detail.copy && detail.rename
      && detail.iframe && detail.hasBackupList,
      JSON.stringify(detail));
    check('详情统计与成就分页保持四类',
      detail.statTabs.join('|') === '通用|物品|生物'
      && detail.advTabs.join('|') === '冒险|下界|农牧业|Minecraft',
      JSON.stringify({ stat: detail.statTabs, adv: detail.advTabs }));

    const errors = await evaluate('window.__e2eErrors || []');
    check('身份组页没有未捕获脚本错误', errors.length === 0, errors.slice(0, 3).join(' | '));

    await sleep(100);
  },
};
