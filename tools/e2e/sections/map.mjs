export default {
  name: 'map',
  description: '地图：Vue 工具栏（复选框/缩放/总览）、canvas 渲染、区块网格持久化',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    await navigate('/samsara/world/map');
    await until("document.getElementById('map-canvas')"
      + " && document.getElementById('map-stats')?.textContent.trim().length > 0",
      30000, '地图数据');
    await sleep(400);

    const layout = await evaluate(`(() => ({
      canvas: Boolean(document.getElementById('map-canvas')),
      width: Math.round(document.getElementById('map-canvas')?.getBoundingClientRect().width || 0),
      height: Math.round(document.getElementById('map-canvas')?.getBoundingClientRect().height || 0),
      stats: document.getElementById('map-stats')?.textContent.trim() || '',
      controls: [...document.querySelectorAll('#panel-map .panel-tools input[type=checkbox]')].length,
      stacks: document.querySelectorAll('#map-stack option').length,
      dimensions: document.querySelectorAll('#map-dimension option').length,
      zoom: document.getElementById('map-zoom-label')?.textContent.trim() || '',
    }))()`);
    check('地图画布与 Vue 工具栏渲染',
      layout.canvas && layout.width > 200 && layout.height > 200
      && layout.controls === 10 && layout.stacks >= 1 && layout.dimensions >= 1
      && layout.stats.includes('栈层'), JSON.stringify(layout));

    const zoom = await evaluate(`(async () => {
      const label = () => document.getElementById('map-zoom-label')?.textContent.trim() || '';
      const before = label();
      document.getElementById('map-zoom-in')?.click();
      await new Promise((resolve) => setTimeout(resolve, 350));
      const after = label();
      document.getElementById('map-zoom-out')?.click();
      await new Promise((resolve) => setTimeout(resolve, 350));
      return { before, after, restored: label() };
    })()`);
    check('Vue 缩放按钮调用 canvas 内核', zoom.before !== zoom.after && zoom.restored === zoom.before,
      JSON.stringify(zoom));

    const chunks = await evaluate(`(async () => {
      const box = document.getElementById('map-show-chunks');
      const legend = () => document.getElementById('map-chunk-grid-legend')?.textContent || '';
      const before = { checked: box.checked, legend: legend() };
      box.checked = false;
      box.dispatchEvent(new Event('change', { bubbles: true }));
      await new Promise((resolve) => setTimeout(resolve, 350));
      const off = { checked: box.checked, legend: legend(), stored: localStorage.getItem('samsara.map.showChunks') };
      box.checked = true;
      box.dispatchEvent(new Event('change', { bubbles: true }));
      await new Promise((resolve) => setTimeout(resolve, 350));
      return { before, off, on: { checked: box.checked, legend: legend(),
        stored: localStorage.getItem('samsara.map.showChunks') } };
    })()`);
    check('区块网格开关联动图例与本地持久化',
      chunks.off.checked === false && chunks.off.legend.includes('关') && chunks.off.stored === '0'
      && chunks.on.checked === true && chunks.on.legend.includes('开') && chunks.on.stored === '1',
      JSON.stringify(chunks));

    const overview = await evaluate(`(async () => {
      document.getElementById('map-overview-toggle')?.click();
      await new Promise((resolve) => setTimeout(resolve, 800));
      const open = {
        visible: !document.getElementById('map-stack-overview')?.hidden,
        stageHidden: document.getElementById('map-stage-anchor')?.hidden === true,
        blocks: document.querySelectorAll('#map-overview-stacks .map-stack-block').length,
        label: document.getElementById('map-overview-toggle')?.textContent.trim() || '',
      };
      document.getElementById('map-overview-toggle')?.click();
      await new Promise((resolve) => setTimeout(resolve, 700));
      return { open, back: !document.getElementById('map-stage-anchor')?.hidden };
    })()`);
    check('世界栈总览切换回详细地图',
      overview.open.visible && overview.open.stageHidden && overview.open.blocks >= 1
      && overview.open.label === '详细地图' && overview.back === true, JSON.stringify(overview));

    const errors = await evaluate('window.__e2eErrors || []');
    check('地图页没有未捕获脚本错误', errors.length === 0, errors.slice(0, 3).join(' | '));

    await sleep(100);
  },
};
