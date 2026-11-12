/** 所有物品栏窗口共用的写入模式定义，避免各处重复维护。 */
export const WRITE_MODES = [
  { id: 'live', label: '实时', hint: '每次操作立即写入；未加载区块或离线玩家会排队，加载/登录后自动应用。' },
  { id: 'preset', label: '预设', hint: '本地编辑，点保存一次性提交。' },
  { id: 'simulate', label: '模拟', hint: '只在本地演练，不会写入服务器。' },
];

export function modeLabel(id) {
  return WRITE_MODES.find((option) => option.id === id)?.label || '模拟';
}

export function modeHint(id) {
  return WRITE_MODES.find((option) => option.id === id)?.hint || '';
}
