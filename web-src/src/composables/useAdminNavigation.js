import { computed } from 'vue';
import { SECTIONS, SECTION_NAV } from '../navigation.js';

/**
 * 从路由 meta 与 mod 列表派生管理端导航状态：二级分区、面包屑、三级子页。
 * AdminLayout 只负责渲染，不再自己维护一套映射。
 */
export function useAdminNavigation(route, mods) {
  const workspace = computed(() => route.meta.workspace || 'samsara');
  const page = computed(() => route.meta.page || 'lottery');
  const activeWorkspace = computed(() => workspace.value);
  const activeModId = computed(() => String(route.params.modId || ''));

  const activeSection = computed(() => {
    if (workspace.value === 'mods') return 'files';
    return route.meta.section || (workspace.value === 'vanilla' ? 'config' : 'economy');
  });

  const sections = computed(() => {
    if (workspace.value === 'mods') return [];
    return SECTIONS[workspace.value] || [];
  });

  const modDisplayName = computed(() => {
    const mod = mods.value.find((item) => item.id === activeModId.value);
    return mod ? mod.label : (activeModId.value || '其他模组');
  });

  const sectionLabel = computed(() => {
    if (workspace.value === 'mods') return modDisplayName.value;
    return sections.value.find((item) => item.id === activeSection.value)?.label
      || String(route.meta.title || '');
  });

  const pageLabel = computed(() => {
    if (workspace.value === 'mods') return String(route.meta.title || '配置文件');
    const title = String(route.meta.title || '');
    return title && title !== sectionLabel.value ? title : '';
  });

  const workspaceShort = computed(() => {
    if (workspace.value === 'vanilla') return '本体';
    if (workspace.value === 'mods') return '其他模组';
    return 'samsara';
  });

  const crumbs = computed(() => {
    const list = [workspaceShort.value];
    if (workspace.value === 'mods') {
      list.push(modDisplayName.value || activeModId.value || '其他模组');
      list.push(pageLabel.value || '配置文件');
      return list;
    }
    const section = sectionLabel.value;
    const current = pageLabel.value;
    // “RCON / RCON 控制台”这类父子同名面包屑合并成一项。
    if (section && current
        && (current.startsWith(section + ' ') || section.startsWith(current + ' '))) {
      list.push(current);
    } else {
      if (section) list.push(section);
      if (current) list.push(current);
    }
    return list;
  });

  const subnavItems = computed(() => {
    if (workspace.value !== 'samsara') return [];
    return SECTION_NAV[activeSection.value] || [];
  });

  const activeSubpage = computed(() => {
    const current = subnavItems.value.find((item) => item.target === route.path);
    if (current) return current.id;
    return String(route.meta.page || '');
  });

  return {
    workspace,
    page,
    activeWorkspace,
    activeModId,
    activeSection,
    sections,
    sectionLabel,
    pageLabel,
    workspaceShort,
    crumbs,
    subnavItems,
    activeSubpage,
    modDisplayName,
  };
}
