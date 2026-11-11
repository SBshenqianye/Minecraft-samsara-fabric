/**
 * 物品搜索：中文 / 英文 / ID / 全拼 / 首字母，并兼容 JEI 风格的
 * @模组 与 #属性 前缀。所有物品搜索框共用这一份匹配逻辑。
 */
function fieldsOf(row) {
  return [
    row.zh,
    row.name,
    row.id,
    row.item,
    row.py,
    row.initials,
  ].filter(Boolean).map((value) => String(value).toLowerCase());
}

function componentText(row) {
  const parts = [
    row.components,
    row.snbt,
    row.potion?.id,
    ...(row.enchantments || []).map((entry) => entry.id),
    ...(row.storedEnchantments || []).map((entry) => entry.id),
    ...(row.attributes || []).map((entry) => entry.id),
  ];
  return parts.filter(Boolean).join(' ').toLowerCase();
}

function matchesToken(row, token) {
  if (!token) return true;
  if (token.startsWith('@')) {
    const mod = token.slice(1);
    if (!mod) return true;
    return String(row.id || row.item || '').toLowerCase().includes(mod);
  }
  if (token.startsWith('#')) {
    const property = token.slice(1);
    if (!property) return true;
    return componentText(row).includes(property);
  }
  return fieldsOf(row).some((value) => value.includes(token));
}

export function matchesItem(row, query) {
  const text = String(query || '').trim().toLowerCase();
  if (!text) return true;
  return text.split(/\s+/).filter(Boolean)
    .every((token) => matchesToken(row, token));
}

export function filterItems(rows, query) {
  const list = rows || [];
  if (!String(query || '').trim()) return list;
  return list.filter((row) => matchesItem(row, query));
}
