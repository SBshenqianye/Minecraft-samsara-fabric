/** 输出格式页的纯展示逻辑：预览片段样式与信息行。 */

export const OUTPUT_COLORS = {
  black: '#000000', dark_blue: '#0000aa', dark_green: '#00aa00', dark_aqua: '#00aaaa',
  dark_red: '#aa0000', dark_purple: '#aa00aa', gold: '#ffaa00', gray: '#aaaaaa',
  dark_gray: '#555555', blue: '#5555ff', green: '#55ff55', aqua: '#55ffff',
  red: '#ff5555', light_purple: '#ff55ff', yellow: '#ffff55', white: '#ffffff',
};

/** 服务端预览片段 → 可用于 :style 的内联样式。 */
export function fragmentStyle(fragment) {
  const style = {};
  const color = OUTPUT_COLORS[fragment?.color];
  if (color) style.color = color;
  if (fragment?.bold) style.fontWeight = '700';
  if (fragment?.italic) style.fontStyle = 'italic';
  const lines = [];
  if (fragment?.underline) lines.push('underline');
  if (fragment?.strikethrough) lines.push('line-through');
  if (lines.length) style.textDecorationLine = lines.join(' ');
  return style;
}

export function messageSummary(message) {
  if (!message) return '';
  return message.key + ' · ' + message.description
    + ' · 变量 ' + (message.variables || 0) + ' · 按钮 ' + (message.buttons || 0);
}

export function previewFragments(result) {
  return (Array.isArray(result?.fragments) ? result.fragments : []).map((fragment) => ({
    text: fragment.text == null ? '' : String(fragment.text),
    style: fragmentStyle(fragment),
    clickable: Boolean(fragment.clickable),
    title: fragment.clickable
      ? String(fragment.clickCommand || '') + ' ' + String(fragment.hover || '')
      : '',
  }));
}

/** 客户端校验：未知/缺失的变量与按钮。 */
export function clientErrors(parts, message) {
  const errors = [];
  if (!message) return errors;
  if (!parts.length) errors.push('模板不能为空');
  const varKeys = [];
  const buttonKeys = [];
  for (const part of parts) {
    if (part.var !== undefined) varKeys.push(part.var);
    if (part.button !== undefined) buttonKeys.push(part.button);
  }
  const knownVars = new Set((message.variables || []).map((item) => item.key));
  const knownButtons = new Set((message.buttons || []).map((item) => item.key));
  const seenVars = new Set();
  const seenButtons = new Set();
  for (const key of varKeys) {
    if (!knownVars.has(key)) errors.push('未知变量：' + key);
    seenVars.add(key);
  }
  for (const key of buttonKeys) {
    if (!knownButtons.has(key)) errors.push('未知按钮：' + key);
    seenButtons.add(key);
  }
  const missing = (message.variables || []).filter((variable) => variable.required
    && !seenVars.has(variable.key));
  const missingButtons = (message.buttons || []).filter((button) => button.required
    && !seenButtons.has(button.key));
  if (missing.length) errors.push('缺少必需变量：' + missing.map((item) => item.key).join(', '));
  if (missingButtons.length) {
    errors.push('缺少必需按钮：' + missingButtons.map((item) => item.label).join(', '));
  }
  return errors;
}

export function varTitle(variable) {
  return variable
    ? variable.label + ' · 样例：' + variable.sample + (variable.required ? ' · 必需' : '')
    : '';
}

export function buttonLabel(part, message) {
  if (part.label) return part.label;
  const definition = (message?.buttons || []).find((item) => item.key === part.button);
  return definition ? definition.label : part.button;
}

export function buttonTitle(part, message) {
  const definition = (message?.buttons || []).find((item) => item.key === part.button);
  return definition ? definition.command + (definition.required ? ' · 必需' : '') : '未知按钮：' + part.button;
}

