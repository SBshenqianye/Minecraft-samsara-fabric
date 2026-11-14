'use strict';

import {
  stackMapRegion,
  stackMapTileUrl,
  stackIconLabel,
} from '../pages/world-stack/model.js';

// 世界栈卡片图标：自定义上传 > 存档 icon.png > 出生点地图缩略图。
// 地图页、世界栈页与公开用户端共用；不依赖地图 canvas。

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

export { buildStackIcon, stackMapTileUrl, stackMapRegion, stackIconLabel };
