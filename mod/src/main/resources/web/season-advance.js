'use strict';

// 全员同意制：只读投票状态页。
(function () {
  const state = { model: null, loading: false };
  const $ = (id) => document.getElementById(id);
  let els = null;
  let pollTimer = null;

  function ensureEls() {
    if (els) return els;
    els = {
      message: $('season-advance-message'),
      stats: $('season-advance-stats'),
      status: $('season-advance-status'),
      error: $('season-advance-error'),
      agreed: $('season-advance-agreed'),
      missing: $('season-advance-missing'),
      eligible: $('season-advance-eligible'),
      agreedCount: $('season-advance-agreed-count'),
      missingCount: $('season-advance-missing-count'),
      eligibleCount: $('season-advance-eligible-count'),
      refresh: $('season-advance-refresh'),
      overview: $('season-advance-overview'),
      detail: $('season-advance-detail'),
    };
    return els;
  }

  function setMessage(text, kind) {
    const el = ensureEls().message;
    if (!el) return;
    el.textContent = text || '';
    el.className = 'message' + (kind ? ' ' + kind : '');
  }

  function fmtTime(value) {
    if (!value) return '—';
    try { return new Date(value).toLocaleString(); } catch (e) { return String(value); }
  }

  function appendChip(parent, label, value) {
    const chip = document.createElement('span');
    chip.className = 'map-stat';
    const name = document.createElement('span');
    name.textContent = label;
    const strong = document.createElement('strong');
    strong.textContent = value;
    chip.append(name, strong);
    parent.append(chip);
  }

  function appendRows(parent, rows) {
    const dl = document.createElement('dl');
    dl.className = 'world-stack-rows';
    for (const [key, value] of rows) {
      const dt = document.createElement('dt');
      dt.textContent = key;
      const dd = document.createElement('dd');
      dd.textContent = value;
      dl.append(dt, dd);
    }
    parent.append(dl);
  }

  function appendMember(parent, item, extra) {
    const row = document.createElement('div');
    row.className = 'season-vote-member' + (extra === 'voted' ? ' voted' : '');
    const name = document.createElement('strong');
    name.textContent = item && item.name ? item.name : '未知玩家';
    const meta = document.createElement('span');
    meta.className = 'muted small';
    meta.textContent = item && item.uuid ? item.uuid : '';
    meta.title = meta.textContent;
    row.append(name, meta);
    if (extra) {
      const badge = document.createElement('span');
      badge.className = 'season-vote-badge' + (extra === 'missing' ? ' missing' : '');
      badge.textContent = extra === 'voted' ? '已同意' : (extra === 'missing' ? '待同意' : extra);
      row.append(badge);
    }
    parent.append(row);
  }

  function clear(el) {
    if (el) el.replaceChildren();
  }

  function render() {
    const e = ensureEls();
    if (!e.stats) return;
    const model = state.model;
    clear(e.stats);
    clear(e.status);
    clear(e.agreed);
    clear(e.missing);
    clear(e.eligible);

    if (!model || !model.active) {
      setMessage((model && model.message) || '没有进行中的开新周目投票。');
      if (e.error) e.error.hidden = true;
      if (e.agreedCount) e.agreedCount.textContent = '0';
      if (e.missingCount) e.missingCount.textContent = '0';
      if (e.eligibleCount) e.eligibleCount.textContent = '0';
      appendRows(e.status, [
        ['状态', '没有进行中的投票'],
        ['如何发起', '无管理员且在线 >1 人时，在游戏内输入 /samsara season advance [same|new]'],
        ['如何同意', '在线玩家输入 /samsara season agree'],
      ]);
      return;
    }

    const agreed = Array.isArray(model.agreed) ? model.agreed : [];
    const missing = Array.isArray(model.missing) ? model.missing : [];
    const eligible = Array.isArray(model.eligible) ? model.eligible : [];
    const required = Number(model.requiredCount) || (agreed.length + missing.length);

    setMessage('投票进行中：' + (model.initiatorName || '未知玩家') + ' 发起 · ' +
        (model.modeLabel || model.mode) + ' · 剩余 ' + (model.remainingText || '—'),
        'ok');
    appendChip(e.stats, '模式', model.modeLabel || model.mode || '—');
    appendChip(e.stats, '发起者', model.initiatorName || '—');
    appendChip(e.stats, '已同意', agreed.length + ' / ' + required);
    appendChip(e.stats, '未同意', String(missing.length));
    appendChip(e.stats, '剩余', model.remainingText || '—');

    const rows = [
      ['投票 ID', model.id || '—'],
      ['模式', model.modeLabel || model.mode || '—'],
      ['发起者', (model.initiatorName || '—') + (model.initiatorUuid ? '（' + model.initiatorUuid + '）' : '')],
      ['状态', model.status || '—'],
      ['发起时间', fmtTime(model.createdAt)],
      ['截止时间', fmtTime(model.expiresAt)],
      ['剩余时间', model.remainingText || '—'],
    ];
    if (model.mode === 'same') {
      rows.push(['目标栈层', model.layerId || '—']);
    } else {
      rows.push(['种子', model.seed == null ? '随机' : String(model.seed)]);
      rows.push(['level-type', model.levelType || 'normal']);
      rows.push(['archiveData', model.archiveData ? 'true' : 'false']);
    }
    appendRows(e.status, rows);

    if (e.error) {
      const error = model.executionError || '';
      e.error.hidden = !error;
      e.error.textContent = error ? '上次执行失败：' + error + '（投票已保留，可在游戏内再次同意重试）' : '';
    }

    if (e.agreedCount) e.agreedCount.textContent = String(agreed.length);
    if (e.missingCount) e.missingCount.textContent = String(missing.length);
    if (e.eligibleCount) e.eligibleCount.textContent = String(eligible.length);

    if (agreed.length === 0) {
      e.agreed.append(emptyLine('还没有玩家同意'));
    } else {
      for (const item of agreed) appendMember(e.agreed, item, 'voted');
    }
    if (missing.length === 0) {
      e.missing.append(emptyLine('全员已同意'));
    } else {
      for (const item of missing) appendMember(e.missing, item, 'missing');
    }
    if (eligible.length === 0) {
      e.eligible.append(emptyLine('名单为空'));
    } else {
      for (const item of eligible) {
        appendMember(e.eligible, item, item.voted ? 'voted' : 'missing');
      }
    }
  }

  function emptyLine(text) {
    const el = document.createElement('p');
    el.className = 'muted small';
    el.textContent = text;
    return el;
  }

  async function load() {
    if (state.loading) return;
    state.loading = true;
    try {
      state.model = await window.SamsaraUI.api('/api/season-advance');
      render();
      refreshCount();
    } catch (error) {
      setMessage(window.SamsaraUI.authMessage(error, '读取投票状态失败'), 'error');
    } finally {
      state.loading = false;
    }
  }

  function refreshCount() {
    const active = state.model && state.model.active ? 1 : 0;
    window.SamsaraUI?.setTabCount('season-advance', active);
    const tab = document.querySelector('.tab[data-panel="panel-season-advance"]');
    if (tab) {
      tab.title = active ? '有进行中的开新周目投票' : '没有进行中的投票';
      tab.setAttribute('aria-label', active ? '投票，有进行中的投票' : '投票，没有进行中的投票');
    }
  }

  function buildNav() {
    const e = ensureEls();
    const items = [];
    const targets = [];
    const add = (id, label, el) => {
      if (!el) return;
      targets.push({ id, el });
      items.push({ label, target: id });
    };
    add('season-advance-overview', '概览', e.overview);
    add('season-advance-detail', '投票状态', e.detail);
    window.SamsaraUI.buildNav('投票导航', items, targets);
  }

  function bind() {
    const e = ensureEls();
    if (e.refresh) e.refresh.addEventListener('click', load);
    if (window.SamsaraUI && window.SamsaraUI.onConnect) {
      window.SamsaraUI.onConnect(() => load());
    }
    if (window.SamsaraUI && window.SamsaraUI.onConnected) {
      window.SamsaraUI.onConnected(() => buildNav());
    }
    if (state.model) render();
    if (!pollTimer) {
      pollTimer = window.setInterval(() => {
        if (state.model && state.model.active
            && window.SamsaraUI?.isActiveTab('season-advance')) {
          load();
        }
      }, 15000);
    }
  }

  function activate() {
    buildNav();
    if (!state.loading) load();
  }

  window.SamsaraSeasonAdvance = { activate, load, reload: load, refreshCount };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bind);
  } else {
    bind();
  }
})();
