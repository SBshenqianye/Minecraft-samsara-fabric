export default {
  name: 'app-config',
  description: '高级配置与圈地范围：Vue 运行状态面板、配置搜索/条件显示、圈地 canvas 预览',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    await navigate('/samsara/config/advanced');
    await until("document.querySelectorAll('#status-chips .status-chip').length >= 6",
      25000, '高级配置状态面板');
    await until("document.querySelectorAll('#files details.file-block').length >= 1",
      25000, '配置项');
    await sleep(300);

    const config = await evaluate(`(() => ({
      chips: [...document.querySelectorAll('#status-chips .status-chip')]
        .map((node) => node.textContent.replace(/\\s+/g, ' ').trim()),
      warningHidden: document.getElementById('status-warning')?.hidden,
      files: document.querySelectorAll('#files details.file-block').length,
      entries: document.querySelectorAll('#files .entry').length,
      summary: document.getElementById('summary')?.textContent.trim() || '',
    }))()`);
    check('运行状态由 Vue 渲染',
      config.chips.length === 6 && config.chips[0].startsWith('Web 版本')
      && config.chips[1].startsWith('模组版本'), JSON.stringify(config.chips));
    check('配置渲染器与摘要仍在内核工作',
      config.files >= 1 && config.entries >= 10 && /^\d+ \/ \d+ 项$/.test(config.summary),
      JSON.stringify({ files: config.files, entries: config.entries, summary: config.summary }));

    const search = await evaluate(`(async () => {
      const input = document.getElementById('search');
      input.value = 'claim';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((resolve) => setTimeout(resolve, 350));
      const filtered = {
        files: document.querySelectorAll('#files details.file-block').length,
        summary: document.getElementById('summary')?.textContent.trim() || '',
      };
      input.value = '';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((resolve) => setTimeout(resolve, 350));
      return { filtered, restored: document.querySelectorAll('#files details.file-block').length };
    })()`);
    check('配置搜索过滤与清空恢复',
      search.filtered.files >= 1 && search.filtered.files < search.restored,
      JSON.stringify(search));

    await navigate('/samsara/config/claim-range');
    await until("document.getElementById('claim-sim-canvas')"
      + " && document.getElementById('claim-sim-canvas').getBoundingClientRect().width > 0",
      25000, '圈地预览画布');
    await sleep(400);

    const claim = await evaluate(`(() => ({
      canvas: Boolean(document.getElementById('claim-sim-canvas')),
      width: Math.round(document.getElementById('claim-sim-canvas')?.getBoundingClientRect().width || 0),
      height: Math.round(document.getElementById('claim-sim-canvas')?.getBoundingClientRect().height || 0),
      files: document.querySelectorAll('#claim-range-files .file-block').length,
      entries: document.querySelectorAll('#claim-range-files .entry').length,
      banner: Boolean(document.getElementById('claim-banner-composer')),
    }))()`);
    check('圈地范围保留 canvas 预览与旗帜合成器',
      claim.canvas && claim.width > 100 && claim.height > 100
      && claim.files >= 1 && claim.entries >= 4 && claim.banner,
      JSON.stringify(claim));

    const errors = await evaluate('window.__e2eErrors || []');
    check('高级配置/圈地页没有未捕获脚本错误', errors.length === 0, errors.slice(0, 3).join(' | '));

    await sleep(100);
  },
};
