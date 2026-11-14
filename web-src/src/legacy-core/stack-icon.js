'use strict';

// 世界栈卡片图标：自定义上传 > 存档 icon.png > 出生点地图缩略图。
// 地图页、世界栈页与公开用户端共用；不依赖地图 canvas。
// ---- 世界栈卡片图标：自定义上传 > 存档 icon.png > 出生点地图缩略图 ----

function stackMapRegion(stack) {
  const rawSpawn = stack && (stack.spawn || (Number.isFinite(Number(stack.spawnX))
    ? { x: stack.spawnX, y: stack.spawnY, z: stack.spawnZ } : null));
  const spawn = rawSpawn;
  let rx = spawn && Number.isFinite(Number(spawn.x))
    ? Math.floor(Number(spawn.x) / 512) : null;
  let rz = spawn && Number.isFinite(Number(spawn.z))
    ? Math.floor(Number(spawn.z) / 512) : null;
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
    const dx = (item[0] + 0.5) * 512 - Number(spawn.x);
    const dz = (item[1] + 0.5) * 512 - Number(spawn.z);
    const distance = dx * dx + dz * dz;
    if (distance < bestDistance) {
      bestDistance = distance;
      best = item;
    }
  }
  return { rx: best[0], rz: best[1] };
}

function stackMapTileUrl(stack) {
  const region = stackMapRegion(stack);
  if (!region) return '';
  // 图标是 <img> 请求，无法携带 Bearer；用公开只读用户端 tile。
  return '/api/user/map/tile?stack=' + encodeURIComponent(stack.id)
    + '&dim=overworld&rx=' + region.rx + '&rz=' + region.rz + '&scale=4&shade=1&v=6';
}

function stackIconLabel(source) {
  return ({
    upload: '自定义图标',
    save: '存档图标',
    map: '出生点地图',
    placeholder: '暂无图标',
  })[source] || '暂无图标';
}

function buildStackIcon(stack, options) {
  const opts = options || {};
  const visual = document.createElement('div');
  visual.className = 'world-stack-card-visual';
  const frame = document.createElement('div');
  frame.className = 'world-stack-icon-frame';
  const image = document.createElement('img');
  image.className = 'world-stack-icon-image';
  image.alt = (stack.label || stack.id || '世界栈') + ' 图标';
  image.loading = 'lazy';
  image.decoding = 'async';
  image.referrerPolicy = 'no-referrer';
  image.hidden = true;
  const placeholder = document.createElement('div');
  placeholder.className = 'world-stack-icon-placeholder';
  placeholder.textContent = '暂无图标';
  const badge = document.createElement('span');
  badge.className = 'world-stack-icon-badge';
  badge.textContent = stackIconLabel('placeholder');
  badge.dataset.source = 'placeholder';

  const candidates = [];
  if (stack.icon && stack.icon.url) {
    candidates.push({ url: stack.icon.url, source: stack.icon.source || 'upload' });
  }
  const mapUrl = stackMapTileUrl(stack);
  if (mapUrl) candidates.push({ url: mapUrl, source: 'map' });
  let next = 0;
  const showNext = () => {
    if (next >= candidates.length) {
      image.hidden = true;
      image.removeAttribute('src');
      placeholder.hidden = false;
      badge.textContent = stackIconLabel('placeholder');
      badge.dataset.source = 'placeholder';
      return;
    }
    const candidate = candidates[next++];
    image.dataset.source = candidate.source;
    badge.textContent = stackIconLabel(candidate.source);
    badge.dataset.source = candidate.source;
    image.hidden = false;
    placeholder.hidden = true;
    image.src = candidate.url;
  };
  image.addEventListener('load', () => {
    image.hidden = false;
    placeholder.hidden = true;
  });
  image.addEventListener('error', showNext);
  if (candidates.length) showNext();
  else {
    placeholder.hidden = false;
    image.hidden = true;
  }
  frame.append(image, placeholder, badge);

  if (opts.admin) {
    const tools = document.createElement('div');
    tools.className = 'world-stack-icon-tools';
    const upload = document.createElement('button');
    upload.type = 'button';
    upload.className = 'ghost';
    upload.textContent = '上传图片';
    upload.addEventListener('click', (event) => {
      event.stopPropagation();
      opts.onUpload?.();
    });
    tools.append(upload);
    if (stack.icon && stack.icon.custom) {
      const reset = document.createElement('button');
      reset.type = 'button';
      reset.className = 'ghost danger';
      reset.textContent = '恢复默认';
      reset.addEventListener('click', (event) => {
        event.stopPropagation();
        opts.onDelete?.();
      });
      tools.append(reset);
    }
    visual.append(frame, tools);
  } else {
    visual.append(frame);
  }
  return visual;
}

window.SamsaraStackIcon = {
  build: buildStackIcon,
  mapTileUrl: stackMapTileUrl,
};

export { buildStackIcon, stackMapTileUrl, stackMapRegion };
