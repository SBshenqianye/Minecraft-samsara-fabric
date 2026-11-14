export default {
  name: 'server-console',
  description: '原生 RCON 控制台：执行 list、输出、补全、清空',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    await navigate('/vanilla/console');
    await until(
      "window.SamsaraUI && document.querySelector('[data-page=\"server-console\"]')",
      20000,
      '原生 RCON 控制台',
    );
    await until("document.getElementById('server-console-panel')", 10000, '控制台面板');

    const state = await evaluate(`(() => ({
      page: !!document.querySelector('[data-page="server-console"]'),
      panel: !!document.getElementById('server-console-panel'),
      legacyServerPage: !!document.getElementById('panel-server'),
      input: !!document.getElementById('server-console-command'),
      send: !!document.getElementById('server-console-send'),
      output: !!document.getElementById('server-console-output'),
      message: !!document.getElementById('server-console-message'),
      navHidden: document.getElementById('global-nav')?.hidden === true,
      noNav: document.querySelector('main.app-shell')?.classList.contains('no-nav') === true,
    }))()`);

    check('RCON 页为原生 Vue 页面', state.page && state.panel && !state.legacyServerPage,
      JSON.stringify({ page: state.page, panel: state.panel, legacy: state.legacyServerPage }));
    check('RCON 输入/发送/输出区存在', state.input && state.send && state.output && state.message,
      JSON.stringify(state));

    const executed = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const input = document.getElementById('server-console-command');
      input.value = 'list';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      document.getElementById('server-console-send').click();
      await sleep(1800);
      return {
        output: document.getElementById('server-console-output')?.textContent || '',
        message: document.getElementById('server-console-message')?.textContent || '',
      };
    })()`);
    check('RCON 可执行 list 并显示输出',
      /players|There are|在线/i.test(executed.output),
      JSON.stringify({ output: executed.output.slice(0, 160) }));
    check('RCON 执行完成有耗时提示', executed.message.includes('完成'),
      executed.message);

    const completed = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const input = document.getElementById('server-console-command');
      input.value = 'samsara ';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
      await sleep(1200);
      const box = document.getElementById('server-console-complete');
      const items = box ? box.querySelectorAll('.console-complete-item').length : 0;
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await sleep(150);
      return { items, hiddenAfter: box ? box.hidden : true };
    })()`);
    check('RCON Tab 补全候选可用', completed.items > 0 && completed.hiddenAfter === true,
      JSON.stringify(completed));

    const cleared = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      document.getElementById('server-console-clear')?.click();
      await sleep(150);
      return {
        output: document.getElementById('server-console-output')?.textContent || '',
        message: document.getElementById('server-console-message')?.textContent || '',
      };
    })()`);
    check('RCON 清空输出', cleared.output === '' && cleared.message === '', JSON.stringify(cleared));

    const errors = await evaluate('window.__e2eErrors || []');
    check('RCON 页没有未捕获脚本错误', errors.length === 0, errors.slice(0, 3).join(' | '));

    await sleep(100);
  },
};
