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
