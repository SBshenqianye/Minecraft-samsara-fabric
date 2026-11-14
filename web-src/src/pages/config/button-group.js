/**
 * 旧内核兼容的分段按钮：返回带 setValue 的 DOM，供 claim-kernel 的朝向控件使用。
 * 视觉/结构与 SamsaraUIKit.buttonGroup 保持一致（.segmented / .segmented-btn / data-value）。
 */
export function createButtonGroup(choices, value, onChange) {
  const wrap = document.createElement('div');
  wrap.className = 'segmented';
  wrap.setAttribute('role', 'group');
  const items = (choices || []).map((choice) => (choice && typeof choice === 'object'
    ? { value: String(choice.value), label: choice.label == null ? String(choice.value) : String(choice.label) }
    : { value: String(choice), label: String(choice) }));
  const buttons = [];
  const select = (next, fire) => {
    const text = String(next);
    for (const button of buttons) {
      const active = button.dataset.value === text;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    }
    if (fire && typeof onChange === 'function') onChange(text);
  };
  for (const choice of items) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'segmented-btn';
    button.dataset.value = choice.value;
    button.textContent = choice.label;
    button.addEventListener('click', () => {
      if (button.classList.contains('active')) return;
      select(choice.value, true);
    });
    buttons.push(button);
    wrap.append(button);
  }
  select(value == null ? '' : value, false);
  wrap.setValue = (next) => select(next, false);
  return wrap;
}
