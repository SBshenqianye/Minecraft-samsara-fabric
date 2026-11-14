let scriptsPromise = null;

/**
 * 按旧页面的加载顺序导入命令式脚本；只执行一次。
 * 复杂页面（地图、世界栈、配置编辑器、权限树、RCON）继续复用这些脚本。
 */
export function loadLegacyAdminScripts() {
  if (!scriptsPromise) {
    scriptsPromise = (async () => {
      await import('../legacy-core/structured.js');
      await import('../legacy-core/form-kit.js');
      await import('../legacy-core/particles.js');
      await import('../legacy-core/banners.js');
      await import('../legacy-core/item-ui.js');
      await import('../legacy-core/value-table.js');
      await import('../legacy-core/page-kit.js');
      await import('../legacy-core/app.js');
      await import('../legacy-core/chat-text.js');
    })().catch((error) => {
      scriptsPromise = null;
      throw error;
    });
  }
  return scriptsPromise;
}
