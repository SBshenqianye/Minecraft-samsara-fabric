/**
 * 世界栈卡片图标的纯逻辑：根据存档出生点与 region 列表选择缩略图 region，
 * 生成公开 tile URL，并给出图标来源标签。
 *
 * 不访问 DOM，供 legacy-core/stack-icon.js 与单测共用。
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
