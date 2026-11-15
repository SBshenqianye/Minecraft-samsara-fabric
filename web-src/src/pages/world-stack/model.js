/**
 * 世界栈卡片图标的纯逻辑：根据存档出生点与 region 列表选择缩略图 region，
 * 生成公开 tile URL，并给出图标来源标签。
 *
 * 不访问 DOM，供页面组件与单测共用。
 */

const REGION_SIZE = 512;
const TILE_SCALE = 4;

export function stackMapRegion(stack) {
  const spawn = stack && (stack.spawn || (Number.isFinite(Number(stack.spawnX))
    ? { x: stack.spawnX, y: stack.spawnY, z: stack.spawnZ } : null));
  const rx = spawn && Number.isFinite(Number(spawn.x))
    ? Math.floor(Number(spawn.x) / REGION_SIZE) : null;
  const rz = spawn && Number.isFinite(Number(spawn.z))
    ? Math.floor(Number(spawn.z) / REGION_SIZE) : null;
  const dimensions = Array.isArray(stack && stack.dimensions) ? stack.dimensions : [];
  const overworld = dimensions.find((dim) => dim && dim.dim === 'overworld') || dimensions[0];
  const regions = overworld && Array.isArray(overworld.regions)
    ? overworld.regions.filter((item) => Array.isArray(item) && item.length >= 2) : [];
  const contains = (x, z) => regions.some((item) => item[0] === x && item[1] === z);
  if (rx != null && rz != null && (regions.length === 0 || contains(rx, rz))) {
    return { rx, rz };
  }
  if (regions.length === 0) return null;
  if (rx == null || rz == null) {
    return { rx: regions[0][0], rz: regions[0][1] };
  }
  // 出生点所在 region 没有生成时，选离出生点最近的一个，避免卡片长期空白。
  let best = regions[0];
  let bestDistance = Infinity;
  for (const item of regions) {
    const dx = (item[0] + 0.5) * REGION_SIZE - Number(spawn.x);
    const dz = (item[1] + 0.5) * REGION_SIZE - Number(spawn.z);
    const distance = dx * dx + dz * dz;
    if (distance < bestDistance) {
      bestDistance = distance;
      best = item;
    }
  }
  return { rx: best[0], rz: best[1] };
}

export function stackMapTileUrl(stack) {
  const region = stackMapRegion(stack);
  if (!region) return '';
  // 图标是 <img> 请求，无法携带 Bearer；用公开只读用户端 tile。
  return '/api/user/map/tile?stack=' + encodeURIComponent(stack.id)
    + '&dim=overworld&rx=' + region.rx + '&rz=' + region.rz
    + '&scale=' + TILE_SCALE + '&shade=1&v=6';
}

const ICON_LABELS = {
  upload: '自定义图标',
  save: '存档图标',
  map: '出生点地图',
  placeholder: '暂无图标',
};

export function stackIconLabel(source) {
  return ICON_LABELS[source] || ICON_LABELS.placeholder;
}

export function fmtBytes(value) {
  const bytes = Number(value) || 0;
  if (bytes <= 0) return '0 B';
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let size = bytes;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return size.toFixed(unit === 0 ? 0 : 1) + ' ' + units[unit];
}

export function fmtTime(value) {
  if (!value) return '—';
  try { return new Date(value).toLocaleString(); } catch (e) { return String(value); }
}

const SOURCE_NAMES = {
  current: '当前世界',
  layer: '栈层',
  imported: '导入',
  demoted: '被压入的栈顶',
  'new-world': '新世界',
  initial: '初始',
  legacy: '旧数据',
};

export function sourceName(source) {
  return SOURCE_NAMES[source] || source || '—';
}

/** 非栈顶按 index 升序；不修改输入数组。 */
export function sortStacksByIndex(stacks) {
  return (Array.isArray(stacks) ? stacks.slice() : []).sort((a, b) => a.index - b.index);
}

/** 顶部统计 chip。 */
export function stackStats(model, stacks) {
  const list = stacks || [];
  const top = list.find((stack) => stack.top);
  const report = model?.lastReport;
  return [
    ['栈层', String(list.length)],
    ['栈顶', top ? top.id : '—'],
    ['总数据', fmtBytes(model?.totalBytes)],
    ['待执行', model?.restartRequired ? '需要重启' : '无'],
    ['导入根', (model?.importRoots || []).length ? model.importRoots.join(' · ') : '仅服务器根'],
    ['上次任务', report ? report.status + ' · ' + (report.message || '') : '无'],
  ];
}

/** 栈位排序视图；order 仍按栈底→栈顶保存，界面反向显示。 */
export function orderView(order, savedOrder, byId) {
  const changed = order.join('|') !== savedOrder.join('|');
  const topMoved = changed && order[order.length - 1] !== 'world';
  const display = (ids) => ids.slice().reverse().join(' → ');
  const view = order.slice().reverse();
  return {
    changed,
    topMoved,
    canSave: changed,
    preview: changed
      ? '原：' + display(savedOrder) + ' ／ 新：' + display(order)
        + (topMoved ? '（保存后提升栈顶，需重启）' : '')
      : '顺序未改变（从上到下 = 栈顶 → 栈底）',
    rows: view.map((id, visualIndex) => {
      const index = order.indexOf(id);
      const stack = byId(id);
      let label = stack && stack.top ? 'world（栈顶）' : id + '（第 ' + (index + 1) + ' 层）';
      if (visualIndex === 0) label += ' · 最上层';
      if (visualIndex === view.length - 1) label += ' · 最下层';
      return {
        id,
        index,
        top: Boolean(stack && stack.top),
        label,
        meta: stack
          ? '顺序 index ' + (stack.index + 1) + ' · seed ' + stack.seed
            + ' · ' + fmtBytes(stack.dataBytes)
          : '',
        upDisabled: index === order.length - 1,
        downDisabled: index === 0,
      };
    }),
  };
}

export function importPositionLabel(item) {
  if (item?.position === 'top') return '栈顶（替换 world）';
  if (item?.position === 'between') return '插到 ' + (item?.afterId || '?') + ' 之上';
  return '栈底';
}

export function pendingImports(model) {
  if (Array.isArray(model?.pendingImports) && model.pendingImports.length) return model.pendingImports;
  return model?.pendingImport ? [model.pendingImport] : [];
}

export function importQueueView(model) {
  return pendingImports(model).map((item, index) => ({
    index: index + 1,
    jobId: item.jobId || '',
    title: '#' + (index + 1),
    label: (item.sourceWorldName || item.source || '世界')
      + ' → ' + importPositionLabel(item)
      + (item.position === 'top' ? '' : ' · ' + (item.targetId || '—')),
    source: item.source || '',
    size: fmtBytes(item.sourceBytes),
  }));
}

export function importPreviewView(preview) {
  if (!preview) return null;
  const rows = [];
  if (preview.source) {
    rows.push(
      ['源目录', preview.source.canonicalPath || preview.source.path],
      ['布局', preview.source.layout],
      ['世界名', preview.source.worldName],
      ['种子', String(preview.source.seed)],
      ['出生点', preview.source.spawnX + ', ' + preview.source.spawnY + ', ' + preview.source.spawnZ],
      ['源大小', fmtBytes(preview.source.sizeBytes) + ' / ' + preview.source.fileCount + ' 文件'],
      ['玩家文件', String(preview.source.playerFiles)],
      ['session.lock', preview.source.sessionLocked ? '仍被占用（拒绝）' : '未占用'],
    );
  }
  rows.push(
    ['位置', preview.position === 'top' ? '栈顶（替换当前 world）'
      : preview.position === 'between'
        ? '插到 ' + preview.afterId + ' 与 ' + (preview.betweenUpper || 'world（栈顶）') + ' 之间'
        : '栈底'],
    ['目标 id', preview.targetId || '—'],
    ['目标 index', String((preview.targetIndex ?? 0) + 1)],
    ['需要空间', fmtBytes(preview.neededBytes) + '（可用 ' + fmtBytes(preview.freeBytes) + '）'],
    ['玩家冲突', String(preview.playerConflicts) + ' 个（保留服务器现有）'],
    ['执行时机', '服务器重启时；源目录只读'],
  );
  return {
    ok: Boolean(preview.ok),
    title: preview.ok ? '导入预览：可以执行' : '导入预览：需要修正',
    rows,
    warnings: Array.isArray(preview.warnings) ? preview.warnings : [],
    steps: Array.isArray(preview.steps) ? preview.steps : [],
    errors: Array.isArray(preview.errors) ? preview.errors : [],
    confirmText: preview.position === 'top'
      ? '我确认：先把当前 world 压入旧栈并备份，再把源世界复制为新的 world；重启后生效'
      : '我确认：复制源目录到新栈层，源目录保持不变；重启后生效',
  };
}

/** 任务列表：排队中的导入/归档/提升/撤销 + 历史 job。 */
export function jobsView(model) {
  const list = [];
  pendingImports(model).forEach((pending, index) => {
    list.push({
      id: pending.jobId || 'import-' + (index + 1),
      type: 'import',
      status: 'queued',
      stage: '等待重启 · 队列第 ' + (index + 1) + ' 项',
      progress: 0,
      message: '重启后导入到 ' + (pending.position === 'top' ? '栈顶 world' : pending.targetId)
        + '；源 ' + pending.source,
      log: [],
    });
  });
  if (model?.pendingArchive) {
    list.push({
      id: model.pendingArchive.jobId, type: 'archive', status: 'queued',
      stage: '等待重启', progress: 0, message: '重启后归档 ' + model.pendingArchive.id, log: [],
    });
  }
  if (model?.pendingPromote) {
    list.push({
      id: model.pendingPromote.jobId, type: 'promote', status: 'queued',
      stage: '等待重启', progress: 0,
      message: '重启后把 ' + model.pendingPromote.promoteId
        + ' 提升为栈顶；原 world 压入 ' + model.pendingPromote.demoteId,
      log: [],
    });
  }
  if (model?.pendingRestore) {
    list.push({
      id: model.pendingRestore.jobId, type: 'restore', status: 'queued',
      stage: '等待重启', progress: 0,
      message: '重启后撤销世界栈操作 ' + model.pendingRestore.operationId, log: [],
    });
  }
  const history = (model?.jobs || []).slice();
  if (model?.lastReport && !history.some((job) => job.id === model.lastReport.id)) {
    history.unshift(model.lastReport);
  }
  for (const job of history) {
    list.push({
      id: job.id || '—',
      type: job.type || 'job',
      status: job.status || '—',
      stage: job.stage || '',
      progress: job.progress || 0,
      message: job.message || '',
      log: (job.log || []).slice(-40),
    });
  }
  return list;
}

export function filesView(model) {
  return (model?.files || []).map((node) => ({
    path: node.path,
    label: node.label,
    note: node.note || '',
    exists: Boolean(node.exists),
    kind: node.kind || 'file',
    depth: node.depth || 0,
    bytes: node.bytes,
    size: node.bytes >= 0 ? fmtBytes(node.bytes) : '',
  }));
}

export function worldStackNav() {
  return [
    { label: '栈层', target: 'world-stack-layers' },
    { label: '栈位排序', target: 'world-stack-order-panel' },
    { label: '导入本地世界', target: 'world-stack-import-panel' },
    { label: '任务与结果', target: 'world-stack-jobs-panel' },
    { label: '文件位置', target: 'world-stack-files-panel' },
  ];
}
