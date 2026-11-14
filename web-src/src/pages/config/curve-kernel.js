/**
 * 轨迹保留曲线编辑器：canvas 拖拽 + 逐行校验 + 服务端容量预估。
 * 算法保持旧实现不变，数据读写与请求由页面注入。
 */
export function createCurveEditor({
  key, file, entry, value, getValue, setValue, requestEstimate, onValidation,
}) {
  const wrap = document.createElement('div');
  wrap.className = 'curve-editor';

  const chartWrap = document.createElement('div');
  chartWrap.className = 'curve-chart-wrap';
  const chart = document.createElement('canvas');
  chart.className = 'curve-chart';
  chart.tabIndex = 0;
  chart.setAttribute('role', 'img');
  chart.title = '拖动圆点修改曲线：横向 = 年龄上限（天），纵向 = 保留比例；右键删除圆点';
  const readout = document.createElement('div');
  readout.className = 'curve-readout';
  chartWrap.append(chart, readout);

  const actions = document.createElement('div');
  actions.className = 'curve-actions';
  const add = document.createElement('button');
  add.type = 'button';
  add.className = 'ghost';
  add.textContent = '+ 增加一档';
  const remove = document.createElement('button');
  remove.type = 'button';
  remove.className = 'ghost';
  remove.textContent = '删除选中';
  const hint = document.createElement('span');
  hint.className = 'muted small';
  hint.textContent = '拖动圆点即可修改：横向 = 年龄上限（天），纵向 = 保留比例。最后一档固定在右边缘，表示“更老”。';
  actions.append(add, remove, hint);

  const error = document.createElement('div');
  error.className = 'curve-error';
  error.setAttribute('role', 'alert');
  const estimate = document.createElement('div');
  estimate.className = 'curve-estimate';
  wrap.append(chartWrap, actions, error, estimate);

  const initial = Array.isArray(value) ? value : (entry.default || []);
  let points = initial.map((item) => ({
    ageDays: Number(item && item.ageDays),
    keepRatio: Number(item && item.keepRatio),
  })).filter((point) => Number.isFinite(point.ageDays) && Number.isFinite(point.keepRatio));
  if (points.length === 0) points = [{ ageDays: 7, keepRatio: 1 }];
  points.sort((a, b) => {
    // 末尾的 ageDays=0 表示“更老”，始终排在最后；其余按年龄升序
    if (a.ageDays === 0 && b.ageDays !== 0) return 1;
    if (b.ageDays === 0 && a.ageDays !== 0) return -1;
    return a.ageDays - b.ageDays;
  });
  let selected = Math.min(1, points.length - 1);
  let hover = -1;
  let drag = null;
  let estimateTimer = 0;
  let maxDays = 30;

  const PAD = { left: 46, right: 18, top: 18, bottom: 30 };
  const HEIGHT = 260;

  const layout = () => {
    const width = Math.max(280, chart.clientWidth || chart.parentElement?.clientWidth || 640);
    const dpr = window.devicePixelRatio || 1;
    if (chart.width !== Math.round(width * dpr) || chart.height !== Math.round(HEIGHT * dpr)) {
      chart.width = Math.round(width * dpr);
      chart.height = Math.round(HEIGHT * dpr);
    }
    chart.style.height = HEIGHT + 'px';
    return {
      width,
      height: HEIGHT,
      left: PAD.left,
      right: width - PAD.right,
      top: PAD.top,
      bottom: HEIGHT - PAD.bottom,
    };
  };

  const plotWidth = (box) => Math.max(10, box.right - box.left);
  const plotHeight = (box) => Math.max(10, box.bottom - box.top);

  const recomputeMaxDays = () => {
    let limit = 7;
    for (const point of points) {
      if (point.ageDays > 0) limit = Math.max(limit, point.ageDays);
    }
    const last = points[points.length - 1];
    const beforeLast = points.length >= 2 ? points[points.length - 2] : null;
    // 末档 age=0 表示“更老”：横轴延伸到 prev*2（至少 prev+30），
    // 与服务端 keepRatioAt 的平滑尾部一致。
    const tail = last && last.ageDays === 0 && beforeLast
      ? Math.max(beforeLast.ageDays * 2, beforeLast.ageDays + 30) : 0;
    maxDays = Math.max(10, Math.ceil(Math.max(limit * 1.18, tail)));
  };

  const xOf = (index, box, span) => {
    const point = points[index];
    if (index === points.length - 1 || point.ageDays === 0) return box.right;
    return box.left + Math.min(1, Math.max(0, point.ageDays / span)) * plotWidth(box);
  };
  const yOf = (ratio, box) => box.top + (1 - Math.min(1, Math.max(0, ratio))) * plotHeight(box);

  const ageAt = (px, box, span) => {
    const ratio = (px - box.left) / plotWidth(box);
    return Math.max(1, Math.round(ratio * span));
  };

  const niceStep = (span) => {
    const target = span / 6;
    const candidates = [1, 2, 5, 10, 20, 30, 50, 100, 200, 500, 1000, 2000, 5000];
    for (const step of candidates) if (step >= target) return step;
    return 10000;
  };

  const readRows = () => points.map((point) => ({
    ageDays: point.ageDays,
    keepRatio: point.keepRatio,
  }));

  const validate = (rows) => {
    const errors = [];
    if (rows.length === 0) {
      errors.push('至少需要一档');
      return errors;
    }
    let previousAge = -1;
    let previousRatio = 1;
    rows.forEach((point, index) => {
      const last = index === rows.length - 1;
      if (!Number.isInteger(point.ageDays) || point.ageDays < 0) {
        errors.push('第 ' + (index + 1) + ' 档 ageDays 必须是非负整数');
      } else if (last) {
        if (point.ageDays !== 0 && point.ageDays <= previousAge) {
          errors.push('最后一档 ageDays 必须为 0（更老）或大于前一档');
        }
      } else if (point.ageDays <= 0) {
        errors.push('只有最后一档的 ageDays 允许为 0（第 ' + (index + 1) + ' 档）');
      } else if (point.ageDays <= previousAge) {
        errors.push('ageDays 必须严格递增（第 ' + (index + 1) + ' 档）');
      }
      if (!Number.isFinite(point.keepRatio) || point.keepRatio < 0 || point.keepRatio > 1) {
        errors.push('keepRatio 必须在 [0,1] 内（第 ' + (index + 1) + ' 档）');
      } else if (point.keepRatio > previousRatio + 1e-9) {
        errors.push('keepRatio 必须单调不增（第 ' + (index + 1) + ' 档）');
      }
      previousAge = Math.max(previousAge, point.ageDays);
      previousRatio = point.keepRatio;
    });
    return errors;
  };

  const humanBytes = (bytes) => {
    const number = Number(bytes) || 0;
    if (number >= 1073741824) return (number / 1073741824).toFixed(2) + ' GiB';
    if (number >= 1048576) return (number / 1048576).toFixed(2) + ' MiB';
    if (number >= 1024) return (number / 1024).toFixed(1) + ' KiB';
    return number + ' B';
  };

  const renderEstimate = (data) => {
    estimate.replaceChildren();
    if (!data || data.ok === false) {
      estimate.textContent = '容量预估暂不可用' + (data && data.error ? '：' + data.error : '');
      return;
    }
    const head = document.createElement('div');
    head.className = 'curve-estimate-head';
    const calibrated = data.actualTotalSamples > 0;
    head.textContent = '容量预估 · 每点 ' + (data.bytesPerSample || 6) + 'B'
      + (calibrated ? '（按实际 .bin 校准）' : '（默认假设）')
      + ' · 新鲜采样 5Hz/天 ' + humanBytes(data.freshBytesPerDay)
      + '（仅估算，按每档保留比例换算）';
    estimate.append(head);
    const actual = document.createElement('div');
    actual.className = 'curve-estimate-line';
    actual.textContent = '当前实际 .bin：' + humanBytes(data.actualTotalBytes || 0)
      + ' · ' + (data.actualFileCount || 0) + ' 个文件'
      + ' · ' + (data.actualPlayerCount || 0) + ' 名玩家'
      + ' · 平均 ' + humanBytes(Math.round(data.actualBytesPerPlayer || 0)) + '/玩家'
      + (calibrated ? ' · 实测 ' + (data.actualBytesPerSample || 0).toFixed(2) + 'B/点' : '');
    estimate.append(actual);
    for (const tier of data.tiers || []) {
      const line = document.createElement('div');
      line.className = 'curve-estimate-line';
      const label = tier.ageDays === 0 ? '更老' : '≤ ' + tier.ageDays + ' 天';
      line.textContent = label + ' · 保留 ' + Math.round(tier.keepRatio * 100) + '%'
        + ' · 每日 ' + humanBytes(tier.bytesPerDay)
        + ' · 有限档累计 ' + humanBytes(tier.cumulativeBytes);
      estimate.append(line);
    }
    const summary = document.createElement('div');
    summary.className = 'curve-estimate-line';
    summary.textContent = '有限档稳态合计 ' + humanBytes(data.steadyBytes)
      + ' · 新鲜数据每日 ' + humanBytes(data.freshBytesPerDay);
    estimate.append(summary);
    if (data.warning) {
      const warn = document.createElement('div');
      warn.className = 'curve-estimate-warn';
      warn.textContent = (data.unbounded || data.keepAll ? '⚠ ' : '') + data.warning;
      estimate.append(warn);
    }
  };

  const fetchEstimate = (rows) => {
    window.clearTimeout(estimateTimer);
    estimateTimer = window.setTimeout(async () => {
      const keepAllEntry = file.entries.find((item) => item.path === 'track.retention.keepAll');
      const keepAll = keepAllEntry ? getValue(keepAllEntry) === true : false;
      const query = '?curve=' + encodeURIComponent(JSON.stringify(rows))
        + '&keepAll=' + (keepAll ? '1' : '0');
      try {
        renderEstimate(await requestEstimate(query));
      } catch (e) {
        renderEstimate({ ok: false, error: e && e.message ? e.message : '请求失败' });
      }
    }, 220);
  };

  const apply = () => {
    const errors = validate(points);
    if (errors.length > 0) {
      error.textContent = errors[0];
      onValidation?.(false);
    } else {
      error.textContent = '';
      onValidation?.(true);
      setValue(points.map((point) => ({
        ageDays: point.ageDays,
        keepRatio: point.keepRatio,
      })));
    }
    fetchEstimate(points);
  };

  const syncReadout = () => {
    readout.replaceChildren();
    points.forEach((point, index) => {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'curve-chip' + (index === selected ? ' selected' : '');
      chip.textContent = (point.ageDays === 0 ? '更老' : '≤ ' + point.ageDays + ' 天')
        + ' · ' + Math.round(point.keepRatio * 100) + '%';
      chip.addEventListener('click', () => {
        selected = index;
        draw();
      });
      readout.append(chip);
    });
    remove.disabled = points.length <= 1;
    chart.dataset.points = JSON.stringify(points);
  };

  const draw = () => {
    const box = layout();
    const span = maxDays;
    const dpr = window.devicePixelRatio || 1;
    const ctx = chart.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, box.width, box.height);

    const styles = getComputedStyle(document.documentElement);
    const ink = styles.getPropertyValue('--text').trim() || '#e2e8f0';
    const muted = styles.getPropertyValue('--muted').trim() || '#94a3b8';
    const line = styles.getPropertyValue('--line').trim() || 'rgba(148,163,184,.25)';
    const accent = styles.getPropertyValue('--accent').trim() || '#5eead4';
    const amber = '#fbbf24';

    // 网格
    ctx.font = '11px ui-monospace, SFMono-Regular, Menlo, monospace';
    ctx.fillStyle = muted;
    ctx.strokeStyle = line;
    ctx.lineWidth = 1;
    const step = niceStep(span);
    for (let age = 0; age <= span; age += step) {
      const x = box.left + (age / span) * plotWidth(box);
      ctx.beginPath();
      ctx.moveTo(x, box.top);
      ctx.lineTo(x, box.bottom);
      ctx.stroke();
      ctx.textAlign = 'center';
      ctx.fillText(String(age), x, box.bottom + 16);
    }
    for (const ratio of [0, 0.25, 0.5, 0.75, 1]) {
      const y = yOf(ratio, box);
      ctx.beginPath();
      ctx.moveTo(box.left, y);
      ctx.lineTo(box.right, y);
      ctx.stroke();
      ctx.textAlign = 'right';
      ctx.fillText(Math.round(ratio * 100) + '%', box.left - 8, y + 4);
    }
    ctx.textAlign = 'center';
    ctx.fillText('年龄（天）', box.left + plotWidth(box) / 2, box.height - 6);

    // 平滑曲线：相邻档位之间线性插值；(0,100%) 作为起点，末档落到右边缘。
    ctx.strokeStyle = accent;
    ctx.lineWidth = 2;
    ctx.beginPath();
    if (points[0].ageDays > 0) {
      ctx.moveTo(box.left, yOf(1, box));
    } else {
      ctx.moveTo(xOf(0, box, span), yOf(points[0].keepRatio, box));
    }
    points.forEach((point, index) => {
      ctx.lineTo(xOf(index, box, span), yOf(point.keepRatio, box));
    });
    ctx.lineTo(box.right, yOf(points[points.length - 1].keepRatio, box));
    ctx.stroke();

    // 圆点
    points.forEach((point, index) => {
      const x = xOf(index, box, span);
      const y = yOf(point.keepRatio, box);
      const isSelected = index === selected;
      const isHover = index === hover;
      ctx.beginPath();
      ctx.arc(x, y, isSelected ? 7 : isHover ? 6 : 5, 0, Math.PI * 2);
      ctx.fillStyle = isSelected ? amber : accent;
      ctx.fill();
      ctx.strokeStyle = 'rgba(15,23,42,.85)';
      ctx.lineWidth = 2;
      ctx.stroke();
      const label = point.ageDays === 0 ? '更老' : point.ageDays + '天';
      ctx.fillStyle = isSelected ? amber : ink;
      ctx.textAlign = index === points.length - 1 ? 'right' : 'center';
      ctx.fillText(label, x, Math.max(box.top + 12, y - 12));
    });

    syncReadout();
    chart.setAttribute('aria-label', points.map((point) => (point.ageDays === 0
      ? '更老 ' + Math.round(point.keepRatio * 100) + '%'
      : '≤' + point.ageDays + ' 天 ' + Math.round(point.keepRatio * 100) + '%')).join('，'));
  };

  const hitTest = (event) => {
    const box = layout();
    const rect = chart.getBoundingClientRect();
    const px = event.clientX - rect.left;
    const py = event.clientY - rect.top;
    let best = -1;
    let bestDistance = 14;
    points.forEach((point, index) => {
      const dx = px - xOf(index, box, maxDays);
      const dy = py - yOf(point.keepRatio, box);
      const distance = Math.hypot(dx, dy);
      if (distance <= bestDistance) {
        bestDistance = distance;
        best = index;
      }
    });
    return best;
  };

  const applyDrag = (event) => {
    if (!drag) return;
    const index = drag.index;
    const box = drag.box;
    const rect = chart.getBoundingClientRect();
    const px = event.clientX - rect.left;
    const py = event.clientY - rect.top;
    const point = points[index];
    const last = index === points.length - 1;
    if (!last) {
      const minAge = index === 0 ? 1 : points[index - 1].ageDays + 1;
      const nextAge = index + 1 < points.length ? points[index + 1].ageDays : 0;
      const maxAge = nextAge > 0 ? Math.max(minAge, nextAge - 1) : drag.span;
      point.ageDays = Math.min(maxAge, Math.max(minAge, ageAt(px, box, drag.span)));
    }
    const maxRatio = index === 0 ? 1 : points[index - 1].keepRatio;
    const minRatio = index + 1 < points.length ? points[index + 1].keepRatio : 0;
    const ratio = 1 - (py - box.top) / plotHeight(box);
    point.keepRatio = Math.round(Math.min(maxRatio, Math.max(minRatio, ratio)) * 100) / 100;
    draw();
  };

  chart.addEventListener('pointerdown', (event) => {
    if (event.button === 2) return;
    const index = hitTest(event);
    if (index < 0) return;
    event.preventDefault();
    selected = index;
    drag = { index, span: maxDays, box: layout() };
    chart.setPointerCapture?.(event.pointerId);
    draw();
  });
  chart.addEventListener('pointermove', (event) => {
    if (drag) {
      applyDrag(event);
      return;
    }
    const index = hitTest(event);
    if (index !== hover) {
      hover = index;
      draw();
    }
    chart.style.cursor = index >= 0 ? 'grab' : 'default';
  });
  const endDrag = (event) => {
    if (!drag) return;
    drag = null;
    chart.releasePointerCapture?.(event.pointerId);
    recomputeMaxDays();
    apply();
    draw();
  };
  chart.addEventListener('pointerup', endDrag);
  chart.addEventListener('pointercancel', endDrag);
  chart.addEventListener('contextmenu', (event) => {
    const index = hitTest(event);
    if (index < 0 || points.length <= 1) return;
    event.preventDefault();
    points.splice(index, 1);
    selected = Math.max(0, Math.min(selected, points.length - 1));
    recomputeMaxDays();
    apply();
    draw();
  });
  chart.addEventListener('mouseleave', () => {
    if (!drag && hover !== -1) {
      hover = -1;
      draw();
    }
  });
  chart.addEventListener('keydown', (event) => {
    if (event.key !== 'Delete' && event.key !== 'Backspace') return;
    if (points.length <= 1) return;
    event.preventDefault();
    points.splice(selected, 1);
    selected = Math.max(0, Math.min(selected, points.length - 1));
    recomputeMaxDays();
    apply();
    draw();
  });
  window.addEventListener('resize', draw);

  add.addEventListener('click', () => {
    const last = points[points.length - 1];
    const insertAt = last && last.ageDays === 0 ? points.length - 1 : points.length;
    const previous = insertAt > 0 ? points[insertAt - 1].ageDays : 0;
    const next = insertAt < points.length ? points[insertAt].ageDays : 0;
    let age = Math.max(1, previous + 30);
    if (next > 0) age = Math.max(previous + 1, Math.min(next - 1, age));
    points.splice(insertAt, 0, {
      ageDays: age,
      keepRatio: insertAt > 0 ? points[insertAt - 1].keepRatio : 1,
    });
    selected = insertAt;
    recomputeMaxDays();
    apply();
    draw();
  });

  remove.addEventListener('click', () => {
    if (points.length <= 1) return;
    points.splice(selected, 1);
    selected = Math.max(0, Math.min(selected, points.length - 1));
    apply();
    draw();
  });


  recomputeMaxDays();
  draw();
  apply();
  return wrap;
}
