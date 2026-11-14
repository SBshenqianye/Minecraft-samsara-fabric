/** 经济业务页的纯格式化与展示数据整理逻辑。 */

export function vp(value) {
  const n = Number(value || 0);
  if (!Number.isFinite(n)) return '0 VP';
  const text = Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
  return text + ' VP';
}

export function dayText(id) {
  if (!id) return '—';
  if (id.startsWith('game-')) return '第 ' + id.slice(5) + ' 游戏日';
  return id;
}

export function timeText(ms) {
  if (!ms) return '—';
  try { return new Date(ms).toLocaleString(); } catch (error) { return String(ms); }
}

export function dateText(ms) {
  if (!ms) return '—';
  try { return new Date(ms).toLocaleDateString(); } catch (error) { return String(ms); }
}

/** 价值表单价：0 明确显示“不收”，避免看起来像缺失。 */
export function valueText(row) {
  return Number(row.value || 0) > 0 ? vp(row.value) : '不收';
}

export function itemTitle(item) {
  return item.zh || item.name || item.item || item.id || '—';
}

/** 概览统计行（顺序与旧界面一致）。 */
export function statsRows(model) {
  const betting = model.bettingDay || {};
  const counting = model.countingDay || {};
  const fund = model.fund || {};
  const gold = model.gold || {};
  return [
    ['开关', model.enabled ? '启用' : '关闭'],
    ['模式', model.mode === 'real' ? '现实日' : '游戏日'],
    ['国际金价', gold.enabled ? Number(gold.price || 0).toFixed(2) + ' USD/oz' : '未启用'],
    ['金块估值', vp(gold.blockValue)],
    ['统计中', dayText(counting.day)],
    ['实时死亡', String(counting.deathCount ?? 0)],
    ['本轮种子', vp(counting.seedValue)],
    ['待开奖奖池', vp(model.activePot ?? counting.pot)],
    ['待开奖票数', String(counting.tickets ?? 0)],
    ['可下注日', dayText(betting.day)],
    ['下注轮已入池', vp(model.bettingPot ?? betting.ownPot ?? betting.pot)],
    ['滚存', vp(model.rollover)],
    ['服务器基金', vp(fund.value)],
  ];
}

export function roundStats(model) {
  const betting = model.bettingDay || {};
  const counting = model.countingDay || {};
  return [
    ['统计日', dayText(counting.day)],
    ['实时死亡', String(counting.deathCount ?? 0)],
    ['本轮种子', vp(counting.seedValue)],
    ['待开奖奖池', vp(model.activePot ?? counting.pot)],
    ['待开奖票数', String(counting.tickets ?? 0) + ' 张'],
    ['可下注日', dayText(betting.day)],
    ['下注轮已入池', vp(model.bettingPot ?? betting.ownPot)],
    ['已售', String(betting.tickets ?? 0) + ' 张'],
    ['截止', betting.cutoff || '—'],
    ['开奖', betting.drawTime || '—'],
  ];
}

export function roundState(model) {
  const state = (model.countingDay || {}).state || '';
  const tone = state === 'BETTING' ? 'ok' : state === 'CANCELLED' ? 'danger' : 'warn';
  const label = state === 'BETTING' ? '统计中'
    : state === 'SETTLED' ? '已结算'
      : state === 'CANCELLED' ? '已取消' : (state || '—');
  return { state, tone, label };
}

export function betSections(model) {
  const betting = model.bettingDay || {};
  const counting = model.countingDay || {};
  return [
    { title: '待开奖 · ' + dayText(counting.day), rows: counting.bets, empty: '待开奖轮还没有人下注。' },
    { title: '下注中 · ' + dayText(betting.day), rows: betting.bets, empty: '下注轮还没有人下注。' },
  ].map((section) => ({
    ...section,
    rows: (Array.isArray(section.rows) ? section.rows : []).map((bet) => ({
      title: bet.name || '—',
      meta: '猜 ' + (bet.guess ?? '—') + ' 人 · ' + (bet.tickets ?? 1) + ' 张 · 投入 ' + vp(bet.value),
    })),
  }));
}

export function claims(model) {
  return (Array.isArray(model.claims) ? model.claims : []).map((claim) => ({
    owner: claim.owner || '—',
    meta: (claim.reason || '待领取') + ' · ' + timeText(claim.at),
    items: Array.isArray(claim.items) ? claim.items : [],
  }));
}

export const INSURANCE_STATUS = {
  ACTIVE: ['生效中', 'ok'],
  PENDING: ['待审核', 'warn'],
  CLAIMED: ['已理赔', ''],
  REVIVE_PENDING: ['待复活', 'warn'],
  REVIVED: ['已复活', 'ok'],
  REVIVE_FAILED: ['复活失败', 'danger'],
  EXPIRED: ['已过期', ''],
  VOID: ['已作废', 'danger'],
};

export function insuranceRows(model) {
  const rows = Array.isArray(model.insurance?.policies) ? model.insurance.policies : [];
  const visible = rows.slice(0, 20).map((policy) => {
    const status = INSURANCE_STATUS[policy.status] || [policy.status || '—', ''];
    return {
      id: policy.id || '—',
      meta: (policy.owner || '—') + ' · 价值 ' + vp(policy.value)
        + ' · 保费 ' + vp(policy.premium)
        + ' · 到期 ' + dateText(policy.expiresAt)
        + (policy.voidReason ? ' · ' + policy.voidReason : ''),
      status: status[0],
      tone: status[1],
    };
  });
  const pending = rows.filter((policy) => policy.status === 'PENDING').length;
  return {
    total: rows.length,
    pending,
    rows: visible,
    more: rows.length > 20 ? '另有 ' + (rows.length - 20) + ' 单未显示。' : '',
  };
}

export function historyRows(model) {
  const rows = Array.isArray(model.history) ? model.history : [];
  return rows.map((result) => ({
    title: dayText(result.day) + ' · 死亡 ' + (result.deathCount ?? 0),
    meta: '中奖票 ' + (result.winnerCount ?? 0)
      + ' · 奖池 ' + vp(result.pot)
      + ' · 抽成 ' + vp(result.rake)
      + (Array.isArray(result.winners) && result.winners.length
        ? ' · ' + result.winners.join('、') : ''),
    rollover: Boolean(result.rollover),
  }));
}

const INSURANCE_COLORS = {
  ACTIVE: '#0d9488', CLAIMED: '#2563eb', REVIVED: '#14b8a6',
  REVIVE_FAILED: '#ef4444', VOID: '#94a3b8', OTHER: '#cbd5e1',
};

/** 概览大屏数据：基金/乐透/保险/历史走势。 */
export function dashboardModel(model) {
  const fund = model.fund || { value: 0, items: [] };
  const betting = model.bettingDay || {};
  const counting = model.countingDay || {};
  const policies = Array.isArray(model.insurance?.policies) ? model.insurance.policies : [];
  const pot = Number(model.activePot || betting.pot || 0);
  const rollover = Number(model.rollover || 0);
  const fundValue = Number(fund.value || 0);
  const fundTotal = Math.max(1, fundValue + pot + rollover);
  const potTotal = Math.max(1, pot + rollover);
  const potPct = Math.round(pot / potTotal * 100);

  const statusCounts = {};
  for (const policy of policies) {
    const status = String(policy.status || 'OTHER').toUpperCase();
    statusCounts[status] = (statusCounts[status] || 0) + 1;
  }
  const totalPolicies = Math.max(1, policies.length);
  const insuranceLegend = Object.entries(statusCounts).map(([status, count]) => ({
    status,
    count,
    color: INSURANCE_COLORS[status] || '#cbd5e1',
    width: Math.round(count / totalPolicies * 100) + '%',
  }));

  const history = (model.history || []).slice(0, 8).reverse();
  const maxPot = Math.max(1, ...history.map((row) => Number(row.pot || 0)));

  return {
    fund: {
      value: fundValue,
      items: (fund.items || []).slice(0, 6),
      itemCount: (fund.items || []).length,
      width: Math.round(fundValue / fundTotal * 100) + '%',
    },
    lottery: {
      pot,
      rollover,
      tickets: Number(betting.tickets || 0),
      sub: (betting.day
        ? '第 ' + String(betting.day).replace(/^game-/, '') + ' 游戏日'
        : '当前轮次')
        + ' · ' + Number(betting.tickets || 0) + ' 张下注 · 滚存 ' + vp(rollover),
      donut: 'conic-gradient(var(--accent) 0 ' + potPct + '%, #f59e0b ' + potPct + '% 100%)',
    },
    insurance: {
      active: statusCounts.ACTIVE || 0,
      total: policies.length,
      sub: '生效 ' + (statusCounts.ACTIVE || 0) + ' · 总保单 ' + policies.length
        + (counting.deathCount != null ? ' · 昨日死亡 ' + counting.deathCount : ''),
      legend: insuranceLegend,
    },
    history: history.map((row) => ({
      day: String(row.day || '').replace(/^game-/, ''),
      pot: Number(row.pot || 0),
      height: Math.max(3, Math.round(Number(row.pot || 0) / maxPot * 100)) + '%',
      winner: Number(row.winnerCount || 0) > 0,
    })),
  };
}

/** 价值表（客户端已拿到的分页行）排序与展示文本。 */
export function valueRows(values) {
  return (Array.isArray(values) ? values : []).map((row) => {
    const zh = row.zh && row.zh !== row.name ? row.zh : '';
    return {
      raw: row,
      name: zh || row.name || row.id || '—',
      id: zh ? (row.name || '') + ' · ' + row.id : (row.id || ''),
      category: row.category || '其他',
      goldLinked: Boolean(row.goldLinked),
      value: valueText(row),
      zero: Number(row.value || 0) <= 0,
    };
  });
}
