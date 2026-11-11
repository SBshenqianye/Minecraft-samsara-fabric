// 物品 tooltip 的中文译名。服务端返回的是英文 locale 的组件名，
// 这里按 MC 简体中文译名映射常见条目；未收录的原样显示英文/ID。
const ENCHANTMENTS = {
  protection: '保护', fire_protection: '火焰保护', feather_falling: '摔落保护',
  blast_protection: '爆炸保护', projectile_protection: '弹射物保护',
  respiration: '水下呼吸', aqua_affinity: '水下速掘', thorns: '荆棘',
  depth_strider: '深海探索者', frost_walker: '冰霜行者',
  binding_curse: '绑定诅咒', vanishing_curse: '消失诅咒',
  soul_speed: '灵魂疾行', swift_sneak: '迅捷潜行',
  sharpness: '锋利', smite: '亡灵杀手', bane_of_arthropods: '节肢杀手',
  knockback: '击退', fire_aspect: '火焰附加', looting: '抢夺',
  sweeping_edge: '横扫之刃', efficiency: '效率', silk_touch: '精准采集',
  unbreaking: '耐久', fortune: '时运', power: '力量', punch: '冲击',
  flame: '火矢', infinity: '无限', luck_of_the_sea: '海之眷顾', lure: '饵钓',
  mending: '经验修补', riptide: '激流', channeling: '引雷', impaling: '穿刺',
  loyalty: '忠诚', multishot: '多重射击', piercing: '穿透',
  quick_charge: '快速装填', density: '致密', breach: '破甲', wind_burst: '风爆',
};

const EFFECTS = {
  speed: '速度', slowness: '缓慢', haste: '急迫', mining_fatigue: '挖掘疲劳',
  strength: '力量', instant_health: '瞬间治疗', instant_damage: '瞬间伤害',
  jump_boost: '跳跃提升', nausea: '反胃', regeneration: '生命恢复',
  resistance: '抗性提升', fire_resistance: '防火', water_breathing: '水下呼吸',
  invisibility: '隐身', blindness: '失明', night_vision: '夜视',
  hunger: '饥饿', weakness: '虚弱', poison: '中毒', wither: '凋零',
  health_boost: '生命提升', absorption: '伤害吸收', saturation: '饱和',
  glowing: '发光', levitation: '飘浮', luck: '幸运', unluck: '霉运',
  slow_falling: '缓降', conduit_power: '潮涌能量', dolphins_grace: '海豚的恩惠',
  bad_omen: '不祥之兆', hero_of_the_village: '村庄英雄', darkness: '黑暗',
  trial_omen: '试炼之兆', raid_omen: '袭击之兆', wind_charged: '蓄风',
  weaving: '织网', oozing: '渗浆', infested: '虫蚀',
};

const ATTRIBUTES = {
  attack_damage: '攻击伤害', attack_speed: '攻击速度', armor: '护甲值',
  armor_toughness: '护甲韧性', knockback_resistance: '击退抗性',
  movement_speed: '移动速度', max_health: '最大生命值', luck: '幸运',
  follow_range: '追踪范围', attack_knockback: '攻击击退',
  flying_speed: '飞行速度', gravity: '重力', scale: '尺寸',
  step_height: '步高', safe_fall_distance: '安全摔落距离',
  fall_damage_multiplier: '摔落伤害倍率', jump_strength: '跳跃力量',
  mining_efficiency: '挖掘效率', sweeping_damage_ratio: '横扫伤害比例',
  max_absorption: '最大伤害吸收', block_interaction_range: '方块交互距离',
  entity_interaction_range: '实体交互距离', block_break_speed: '方块破坏速度',
  submersion_speed: '水下速度', lava_speed: '熔岩速度', burning_time: '燃烧时间',
  explosion_knockback_resistance: '爆炸击退抗性', movement_efficiency: '移动效率',
  oxygen_bonus: '氧气加成', water_movement_efficiency: '水中移动效率',
};

const RARITY = {
  common: '普通', uncommon: '少见', rare: '稀有', epic: '史诗',
};

function pathOf(id) {
  const text = String(id || '');
  const colon = text.lastIndexOf(':');
  return (colon >= 0 ? text.slice(colon + 1) : text).replace(/^generic\./, '');
}

export function zhEnchantment(id, fallback) {
  return ENCHANTMENTS[pathOf(id)] || fallback || pathOf(id);
}

export function zhEffect(id, fallback) {
  return EFFECTS[pathOf(id)] || fallback || pathOf(id);
}

export function zhAttribute(id, fallback) {
  return ATTRIBUTES[pathOf(id)] || fallback || pathOf(id);
}

export function zhRarity(value) {
  return RARITY[String(value || '').toLowerCase()] || '';
}
