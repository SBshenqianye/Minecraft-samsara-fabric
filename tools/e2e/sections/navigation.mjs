export default {
  name: 'navigation',
  description: '页内导航：子项高亮、折叠区滚动、无导航页面清理',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    await navigate('/samsara/config/advanced');
    await until("document.querySelectorAll('#page-nav .nav-item').length > 0", 30000, '配置导航');

    const child = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
      const items = [...document.querySelectorAll('#page-nav .nav-item')];
      const button = items.find((node) => node.textContent.trim().startsWith('where.showChunk'));
      button?.click();
      await sleep(1700);
      const active = items.filter((node) => node.classList.contains('active'))
        .map((node) => node.textContent.trim());
      const target = button ? document.getElementById(button.dataset.target) : null;
      return {
        clicked: button?.textContent.trim() || '',
        active,
        targetTop: target ? Math.round(target.getBoundingClientRect().top) : null,
        navTop: Math.round(document.getElementById('page-nav')?.getBoundingClientRect().top || 0),
      };
    })()`);
    check('点击子导航后高亮子项而不是父级',
        child.active.length === 1 && child.active[0].startsWith('where.showChunk'),
        JSON.stringify(child));
    check('子导航滚动到吸顶导航下方',
        child.targetTop != null && Math.abs(child.targetTop - child.navTop) <= 2,
        JSON.stringify(child));

    const scrolled = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
      window.scrollTo({ top: 2500, behavior: 'instant' });
      await sleep(300);
      const items = [...document.querySelectorAll('#page-nav .nav-item')];
      const button = items.find((node) => node.textContent.trim().startsWith('view.protectViewer'));
      button?.click();
      await sleep(1400);
      const target = button ? document.getElementById(button.dataset.target) : null;
      const nav = document.getElementById('page-nav');
      return {
        clicked: button?.textContent.trim() || '',
        active: items.filter((node) => node.classList.contains('active'))
          .map((node) => node.textContent.trim()),
        targetTop: target ? Math.round(target.getBoundingClientRect().top) : null,
        navTop: Math.round(nav?.getBoundingClientRect().top || 0),
        scrollY: Math.round(window.scrollY),
      };
    })()`);
    check('页面已滚动时点击导航仍落在点击项',
        scrolled.active.length === 1 && scrolled.active[0].startsWith('view.protectViewer')
        && scrolled.targetTop != null && Math.abs(scrolled.targetTop - scrolled.navTop) <= 2,
        JSON.stringify(scrolled));

    const collapsed = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
      const items = [...document.querySelectorAll('#page-nav .nav-item')];
      const button = items.find((node) => node.textContent.trim().startsWith('tpa.enabled'));
      const target = button ? document.getElementById(button.dataset.target) : null;
      const group = target?.closest('details.group-block');
      if (group) group.open = false;
      await sleep(200);
      button?.click();
      await sleep(1700);
      return {
        clicked: button?.textContent.trim() || '',
        groupOpen: group?.open === true,
        active: items.filter((node) => node.classList.contains('active'))
          .map((node) => node.textContent.trim()),
        targetTop: target ? Math.round(target.getBoundingClientRect().top) : null,
        navTop: Math.round(document.getElementById('page-nav')?.getBoundingClientRect().top || 0),
      };
    })()`);
    check('折叠分组内的目标会先展开再对齐',
        collapsed.groupOpen && collapsed.active.length === 1
        && collapsed.active[0].startsWith('tpa.enabled')
        && Math.abs(collapsed.targetTop - collapsed.navTop) <= 2,
        JSON.stringify(collapsed));

    const cleared = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
      const waitText = async (selector, text, ms = 8000) => {
        const deadline = Date.now() + ms;
        while (Date.now() < deadline) {
          const node = [...document.querySelectorAll(selector)]
            .find((item) => item.textContent.trim() === text);
          if (node) return node;
          await sleep(100);
        }
        return null;
      };
      const community = await waitText('.section-tabs .section-tab, .subnav-item', '社区');
      community?.click();
      const announcements = await waitText('.section-subnav .subnav-item, .subnav-item', '公告');
      announcements?.click();
      const deadline = Date.now() + 8000;
      while (Date.now() < deadline && location.pathname !== '/samsara/community/announcements') {
        await sleep(100);
      }
      await sleep(600);
      const nav = document.getElementById('page-nav');
      return {
        path: location.pathname,
        hidden: nav?.hidden,
        title: nav?.querySelector('.nav-title')?.textContent?.trim() || '',
        items: nav?.querySelectorAll('.nav-item').length || 0,
      };
    })()`);
    check('切到无页内导航的 Tab 后旧导航被清理',
        cleared.path === '/samsara/community/announcements'
        && cleared.hidden === true && cleared.items === 0 && cleared.title === '',
        JSON.stringify(cleared));

    await navigate('/samsara/config/editor');
    await until("document.querySelectorAll('#page-nav .nav-item').length > 0", 30000, '配置编辑器导航');
    const editor = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
      const items = [...document.querySelectorAll('#page-nav .nav-item')];
      const page = items.find((node) => node.textContent.includes('命令开关'));
      page?.click();
      await sleep(900);
      return {
        clicked: page?.textContent.trim() || '',
        active: items.filter((node) => node.classList.contains('active'))
          .map((node) => node.textContent.trim()),
      };
    })()`);
    check('配置编辑器页导航只高亮当前页面',
        editor.active.length === 1 && editor.active[0].includes('命令开关'),
        JSON.stringify(editor));
  },
};
