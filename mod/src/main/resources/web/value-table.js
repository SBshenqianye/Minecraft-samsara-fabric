'use strict';

// 懒加载价值表：服务端分页 + 搜索；管理端与公开端共用同一套渲染。
(function () {
  const $ = (id) => document.getElementById(id);

  function make(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function create(options) {
    const opts = options || {};
    const root = $(opts.rootId);
    const scroll = $(opts.scrollId);
    const emptyEl = $(opts.emptyId);
    const countEl = $(opts.countId);
    const search = $(opts.searchId);
    const category = $(opts.categoryId);
    const sort = $(opts.sortId);
    const limit = opts.limit || 100;
    const state = {
      items: [],
      total: 0,
      offset: 0,
      q: '',
      category: '',
      sort: 'value-desc',
      loading: false,
      loaded: false,
      seq: 0,
      timer: 0,
      observer: null,
    };

    function valueText(row) {
      const value = Number(row.value || 0);
      return value > 0 ? window.SamsaraItem.fmtVp(value) : '不收';
    }

    function render(reset) {
      if (!root) return;
      const keepTop = reset ? 0 : (scroll ? scroll.scrollTop : 0);
      root.replaceChildren();
      if (countEl) countEl.textContent = state.items.length + ' / ' + state.total + ' 项';
      if (!state.items.length) {
        if (emptyEl) emptyEl.hidden = false;
        if (scroll) scroll.hidden = true;
        return;
      }
      if (emptyEl) emptyEl.hidden = true;
      if (scroll) scroll.hidden = false;
      for (const row of state.items) {
        const line = make('div', 'lottery-value-row' + (Number(row.value || 0) > 0 ? '' : ' zero'));
        const item = make('div', 'lottery-value-item');
        item.append(window.SamsaraItem.icon(row, { size: 'sm' }));
        const zh = row.zh && row.zh !== row.name ? row.zh : '';
        item.append(make('span', 'lottery-value-name', zh || row.name || row.id));
        item.append(make('span', 'lottery-value-id',
          zh ? (row.name || '') + ' · ' + row.id : row.id));
        if (row.goldLinked) {
          item.append(make('span', 'lottery-value-gold', '金价'));
        }
        line.append(item, make('span', 'lottery-value-cat', row.category || '其他'));
        line.append(make('strong', 'num', valueText(row)));
        root.append(line);
      }
      if (state.items.length < state.total) {
        const more = make('button', 'ghost lottery-more',
          '加载更多（' + state.items.length + ' / ' + state.total + '）');
        more.type = 'button';
        more.addEventListener('click', () => load(false));
        root.append(more);
        if (state.observer) state.observer.disconnect();
        if (typeof IntersectionObserver === 'function') {
          state.observer = new IntersectionObserver((entries) => {
            if (entries.some((entry) => entry.isIntersecting)) load(false);
          }, { root: scroll || null, rootMargin: '120px' });
          state.observer.observe(more);
        }
      } else if (state.observer) {
        state.observer.disconnect();
        state.observer = null;
      }
      if (scroll) scroll.scrollTop = keepTop;
    }

    async function load(reset) {
      if (!root || !opts.fetch) return;
      if (state.loading && !reset) return;
      if (reset) {
        state.items = [];
        state.total = 0;
        state.offset = 0;
        state.loaded = false;
      }
      const seq = ++state.seq;
      state.loading = true;
      try {
        const params = new URLSearchParams();
        if (state.q) params.set('q', state.q);
        if (state.category) params.set('category', state.category);
        params.set('sort', state.sort);
        params.set('offset', String(state.offset));
        params.set('limit', String(limit));
        const model = await opts.fetch(opts.endpoint + '?' + params.toString());
        if (seq !== state.seq) return;
        const rows = Array.isArray(model.values) ? model.values : [];
        state.items = reset ? rows : state.items.concat(rows);
        state.total = Number(model.total || state.items.length);
        state.offset = state.items.length;
        state.loaded = true;
        render(Boolean(reset));
      } catch (error) {
        if (seq === state.seq && opts.onError) {
          opts.onError('价值表读取失败：' + (error.message || '未知错误'));
        }
      } finally {
        if (seq === state.seq) state.loading = false;
      }
    }

    function ensure() {
      if (!state.loaded && !state.loading) load(true);
    }

    function refresh() {
      load(true);
    }

    if (search) {
      search.addEventListener('input', () => {
        window.clearTimeout(state.timer);
        state.timer = window.setTimeout(() => {
          state.q = search.value.trim();
          load(true);
        }, 240);
      });
    }
    if (category) {
      category.addEventListener('change', () => {
        state.category = category.value;
        load(true);
      });
    }
    if (sort) {
      sort.addEventListener('change', () => {
        state.sort = sort.value;
        load(true);
      });
    }
    if (scroll) {
      scroll.addEventListener('scroll', () => {
        if (state.loading || state.items.length >= state.total) return;
        if (scroll.scrollTop + scroll.clientHeight >= scroll.scrollHeight - 120) {
          load(false);
        }
      });
    }

    return { ensure, refresh, load, state };
  }

  window.SamsaraValueTable = { create };
})();
