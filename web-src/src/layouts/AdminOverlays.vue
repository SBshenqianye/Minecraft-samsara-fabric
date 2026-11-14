<template>
<div id="app-skeleton" class="app-skeleton" hidden>
  <div class="skeleton-side">
    <div class="skeleton-bar w45"></div>
    <div class="skeleton-bar"></div>
    <div class="skeleton-bar w80"></div>
    <div class="skeleton-bar w65"></div>
    <div class="skeleton-bar w75"></div>
    <div class="skeleton-bar w50"></div>
  </div>
  <div class="skeleton-main">
    <div class="skeleton-card tall"></div>
    <div class="skeleton-card">
      <div class="skeleton-bar w35"></div>
      <div class="skeleton-bar w90"></div>
      <div class="skeleton-bar w70"></div>
    </div>
    <div class="skeleton-card">
      <div class="skeleton-bar w30"></div>
      <div class="skeleton-bar w85"></div>
      <div class="skeleton-bar w60"></div>
    </div>
  </div>
</div>

<dialog id="token-dialog" class="app-dialog token-dialog">
  <form method="dialog" class="dialog-body" id="token-form">
    <h3>输入访问 token</h3>
    <p class="dialog-sub">token 在服务器启动日志或 <code>config/samsara/web.yml</code> 里，只需填写一次；之后保存在当前浏览器会话中，不再显示。</p>
    <label class="field-card">
      <span>访问 token</span>
      <input id="token-input" type="password" placeholder="粘贴 web.yml 里的 token" autocomplete="off">
      <small>只存 sessionStorage，不写 URL，也不进日志。</small>
    </label>
    <div id="token-error" class="message error" role="status"></div>
    <div class="dialog-actions">
      <span class="spacer"></span>
      <button type="button" id="token-connect" class="primary">连接</button>
    </div>
  </form>
</dialog>

<dialog id="config-editor-preview-dialog" class="app-dialog">
  <form method="dialog" class="dialog-body">
    <h3>预览变更</h3>
    <p id="config-editor-preview-sub" class="dialog-sub"></p>
    <div id="config-editor-preview-errors" class="message error" hidden></div>
    <pre id="config-editor-preview-diff" class="config-editor-diff"></pre>
    <div class="dialog-actions">
      <span class="spacer"></span>
      <button type="button" id="config-editor-preview-cancel" class="ghost">取消</button>
      <button type="button" id="config-editor-preview-confirm" class="primary">确认保存</button>
    </div>
  </form>
</dialog>

<input id="world-stack-icon-input" type="file" accept="image/png,image/jpeg,image/webp" hidden>

<datalist id="identity-candidates"></datalist>
<datalist id="identity-data-uuids"></datalist>

<button id="back-to-top" class="back-to-top" type="button" title="回到顶部" hidden>↑</button>
</template>

<script setup>
// 旧命令式内核依赖的全局浮窗 / 对话框；从旧 index.html 迁入，保持同一批 DOM id。
defineOptions({ name: 'AdminOverlays' });
</script>
