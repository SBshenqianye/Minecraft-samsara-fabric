let scriptsPromise = null;

/**
 * 按旧页面的加载顺序导入命令式脚本；只执行一次。
 * 复杂页面（地图、世界栈、配置编辑器、权限树、RCON）继续复用这些脚本。
 */
export function loadLegacyAdminScripts() {
  if (!scriptsPromise) {
    scriptsPromise = (async () => {
      await import('../../../mod/src/main/resources/web/structured.js');
      await import('../../../mod/src/main/resources/web/form-kit.js');
      await import('../../../mod/src/main/resources/web/particles.js');
      await import('../../../mod/src/main/resources/web/banners.js');
      await import('../../../mod/src/main/resources/web/item-ui.js');
      await import('../../../mod/src/main/resources/web/value-table.js');
      await import('../../../mod/src/main/resources/web/lottery.js');
      await import('../../../mod/src/main/resources/web/page-kit.js');
      await import('../../../mod/src/main/resources/web/app.js');
      await import('../../../mod/src/main/resources/web/chat-text.js');
      await import('../../../mod/src/main/resources/web/config-editor.js');
      await import('../../../mod/src/main/resources/web/map.js');
      await import('../../../mod/src/main/resources/web/world-stack.js');
      await import('../../../mod/src/main/resources/web/output.js');
      await import('../../../mod/src/main/resources/web/identity.js');
      await import('../../../mod/src/main/resources/web/server.js');
    })().catch((error) => {
      scriptsPromise = null;
      throw error;
    });
  }
  return scriptsPromise;
}
