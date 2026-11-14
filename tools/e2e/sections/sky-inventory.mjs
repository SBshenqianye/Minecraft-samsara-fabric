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

export default {
  name: 'sky-inventory',
  description: '天网统一页：玩家背包浮窗 68 格、3D 纸盒人、Web 背包专用编辑面板',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep, base, token } = ctx;

    await api(base, token, '/api/server/console', {
      method: 'POST',
      body: JSON.stringify({ command: 'player E2EInv spawn' }),
    }).catch(() => {});
    for (let i = 0; i < 30; i += 1) {
      await sleep(500);
      const current = await api(base, token,
          '/api/inventory?player=E2EInv&scope=inventory');
      if (current.body?.online) break;
    }

    try {
      const stone = await api(base, token, '/api/inventory/item', {
        method: 'POST',
        body: JSON.stringify({ id: 'minecraft:stone', count: 1 }),
      });
      const seeded = Array(68).fill('');
      if (stone.body?.encoded) seeded[0] = stone.body.encoded;
      await api(base, token, '/api/inventory/web/write', {
        method: 'POST',
        body: JSON.stringify({ slots: seeded }),
      });

      await navigate('/samsara/sky/containers');
      await until("document.querySelector('.container-page.embedded')", 30000, '天网统一页');
      await until("document.querySelectorAll('.map-marker').length >= 1", 30000, '天网地图标记');

      const playerWindow = await evaluate(`(async () => {
        const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
        let openedVia = '';
        const deadline = Date.now() + 12000;
        while (!document.querySelector('.floating-window .inventory-window-player')
            && Date.now() < deadline) {
          for (const marker of [...document.querySelectorAll('.map-marker')]) {
            marker.click();
            await sleep(250);
            const cell = [...document.querySelectorAll('.cluster-cell')]
              .find((node) => node.querySelector('.marker-head'));
            if (cell) {
              cell.click();
              openedVia = 'map';
              break;
            }
          }
          if (openedVia) break;
          await sleep(300);
        }
        const slotDeadline = Date.now() + 20000;
        while (Date.now() < slotDeadline
            && (document.querySelectorAll('.floating-window .inventory-window-player .slot').length < 68
              || !document.querySelector('.floating-window .inventory-window-player .pm3d-model'))) {
          await sleep(150);
        }
        const win = [...document.querySelectorAll('.floating-window')]
          .find((node) => node.querySelector('.inventory-window-player'));
        const gear = win?.querySelector('.workbench-row-gear');
        const main = win?.querySelector('.workbench-row-main');
        const hotbar = win?.querySelector('.workbench-row-hotbar');
        const grids = gear ? [...gear.querySelectorAll('.slot-grid')] : [];
        const columns = (grid) => grid
          ? getComputedStyle(grid).gridTemplateColumns.split(' ').length : 0;
        return {
          openedVia,
          windows: document.querySelectorAll('.floating-window').length,
          slots: win ? win.querySelectorAll('.slot').length : 0,
          doll: !!(win && win.querySelector('.pm3d-model')),
          dollInGear: !!(gear && gear.querySelector('.pm3d')),
          ender: !!(win && win.querySelector('.workbench-row-ender')),
          gearColumns: grids.length ? columns(grids[0]) : 0,
          offhandColumns: grids.length > 1 ? columns(grids[grids.length - 1]) : 0,
          gearTop: !!gear && !!main
            && gear.getBoundingClientRect().top < main.getBoundingClientRect().top,
          hotbarBelow: !!main && !!hotbar
            && hotbar.getBoundingClientRect().top > main.getBoundingClientRect().bottom,
          gearSlotWidth: gear?.querySelector('.slot')?.getBoundingClientRect().width || 0,
          mainSlotWidth: main?.querySelector('.slot')?.getBoundingClientRect().width || 0,
          errors: window.__e2eErrors || [],
        };
      })()`);
      check('地图聚合打开玩家背包浮窗且 68 格',
          Boolean(playerWindow.openedVia) && playerWindow.slots === 68 && playerWindow.ender,
          JSON.stringify(playerWindow));
      check('玩家窗口 3D 纸盒人在装备行、装备/主背包等宽且快捷栏在下',
          playerWindow.doll && playerWindow.dollInGear
          && playerWindow.gearTop && playerWindow.hotbarBelow
          && playerWindow.gearColumns === 4 && playerWindow.offhandColumns === 1
          && playerWindow.gearSlotWidth >= 40
          && Math.abs(playerWindow.gearSlotWidth - playerWindow.mainSlotWidth) <= 1,
          JSON.stringify(playerWindow));

      const drawer = await evaluate(`(async () => {
        const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'e', bubbles: true }));
        const deadline = Date.now() + 20000;
        while (Date.now() < deadline
            && (document.querySelectorAll('.global-inventory-grid-wrap .slot').length < 68
              || !document.querySelector('.global-inventory-panel .creative-library .creative-slot'))) {
          await sleep(150);
        }
        const panel = document.querySelector('.global-inventory-panel');
        const tab = document.querySelector('.global-inventory-tab');
        const tabs = [...document.querySelectorAll('.global-inventory-panel .creative-tabs button')];
        const grid = document.querySelector('.global-inventory-panel .slot-grid');
        const result = {
          panel: Boolean(panel),
          slots: document.querySelectorAll('.global-inventory-grid-wrap .slot').length,
          library: !!document.querySelector('.global-inventory-panel .creative-library .creative-slot'),
          debugTab: tabs.some((button) => (button.getAttribute('title')
            || button.getAttribute('aria-label') || '').trim() === '调试方块'),
          tabVisible: !!tab && tab.getBoundingClientRect().width > 0,
          gridWidth: grid ? Math.round(grid.getBoundingClientRect().width) : 0,
          errors: window.__e2eErrors || [],
        };
        return result;
      })()`);
      check('Web 背包浮窗 68 格 + 创造物品库，打开时右侧标签隐藏',
          drawer.panel && drawer.slots === 68 && drawer.library
          && drawer.debugTab && drawer.tabVisible === false,
          JSON.stringify(drawer));

      const editor = await evaluate(`(async () => {
        const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
        document.querySelectorAll('.global-inventory-grid-wrap .slot')[0]?.click();
        const deadline = Date.now() + 15000;
        while (Date.now() < deadline
            && !document.querySelector('.global-inventory-editor .item-editor')) {
          await sleep(150);
        }
        const panel = document.querySelector('.global-inventory-editor');
        const result = {
          panel: Boolean(panel),
          dedicated: !!panel && getComputedStyle(panel).position !== 'absolute',
          inputs: panel ? panel.querySelectorAll('input, textarea, select').length : 0,
          chips: document.querySelectorAll('.global-inventory-panel .cursor-chip').length,
          trash: document.querySelectorAll('.global-inventory-grid-wrap .workbench-trash-inline').length,
        };
        [...(panel?.querySelectorAll('button') || [])]
          .find((button) => button.textContent.trim() === '×')?.click();
        [...document.querySelectorAll('.global-inventory-panel button')]
          .find((button) => button.textContent.trim() === '×')?.click();
        return result;
      })()`);
      check('组件编辑使用 Web 背包专用面板、无光标 chip、垃圾桶在原位',
          editor.panel && editor.dedicated && editor.inputs > 0
          && editor.chips === 0 && editor.trash === 1,
          JSON.stringify(editor));

      check('天网页没有未捕获脚本错误',
          playerWindow.errors.length === 0 && drawer.errors.length === 0,
          [...playerWindow.errors, ...drawer.errors].slice(0, 3).join(' | '));
    } finally {
      await api(base, token, '/api/inventory/web/write', {
        method: 'POST',
        body: JSON.stringify({ slots: Array(68).fill('') }),
      }).catch(() => {});
      await api(base, token, '/api/server/console', {
        method: 'POST',
        body: JSON.stringify({ command: 'player E2EInv kill' }),
      }).catch(() => {});
    }
  },
};
