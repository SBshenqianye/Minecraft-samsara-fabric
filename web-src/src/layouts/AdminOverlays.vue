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

<dialog id="server-audit-diff-wrap" class="app-dialog server-audit-dialog">
  <div class="dialog-body">
    <div class="dialog-head">
      <div>
        <h3 id="server-audit-diff-title">差异</h3>
        <p class="dialog-sub">预览快照与当前文件的差异；回滚前请确认影响范围。</p>
      </div>
      <button id="server-audit-diff-close" class="ghost dialog-close" type="button" title="关闭" aria-label="关闭">×</button>
    </div>
    <div id="server-audit-changes" class="server-audit-changes"></div>
    <div id="server-audit-diffs" class="server-audit-diffs"></div>
    <pre id="server-audit-diff" class="server-audit-diff" aria-live="polite" hidden></pre>
  </div>
</dialog>

<dialog id="uuid-dialog" class="app-dialog">
  <form method="dialog" class="dialog-body" id="uuid-form">
    <h3 id="uuid-title">选择 UUID</h3>
    <p id="uuid-desc" class="dialog-sub"></p>
    <label class="field-card">
      <span>名称 / UUID</span>
      <input id="uuid-input" type="text" list="identity-candidates" autocomplete="off"
             placeholder="输入玩家名、正版账号 UUID 或离线账号 UUID">
      <small>输入名称后会自动计算离线账号 UUID，并尝试从 Mojang 获取正版账号 UUID。</small>
    </label>
    <div id="uuid-resolved" class="resolve-result" hidden></div>
    <div class="dialog-actions">
      <button type="button" id="uuid-clear" class="ghost danger">清空当前值</button>
      <span class="spacer"></span>
      <button type="button" id="uuid-cancel" class="ghost">取消</button>
      <button type="button" id="uuid-apply" class="primary">确定</button>
    </div>
  </form>
</dialog>

<dialog id="assign-dialog" class="app-dialog">
  <form method="dialog" class="dialog-body" id="assign-form">
    <h3>放入身份组</h3>
    <p id="assign-target" class="dialog-sub"></p>
    <label class="field-card">
      <span>目标组</span>
      <select id="assign-group"></select>
      <small>没有合适的组时，先关闭本窗口、在下方新增一个组。</small>
    </label>
    <label class="field-card">
      <span>放到哪个区域</span>
      <select id="assign-role">
        <option value="data">数据 UUID（可共享）</option>
        <option value="premium">正版账号 UUID（仅一个组）</option>
        <option value="offline">离线账号 UUID（仅一个组）</option>
      </select>
    </label>
    <div id="assign-error" class="message error"></div>
    <div class="dialog-actions">
      <span class="spacer"></span>
      <button type="button" id="assign-cancel" class="ghost">取消</button>
      <button type="button" id="assign-apply" class="primary">放入</button>
    </div>
  </form>
</dialog>

<dialog id="rebind-dialog" class="app-dialog">
  <form method="dialog" class="dialog-body" id="rebind-form">
    <h3>重绑 data UUID</h3>
    <p class="dialog-sub">组：<strong id="rebind-group"></strong></p>
    <p class="dialog-note">data UUID 是玩家数据的存储钥匙。重绑不会删除旧数据：程序会先备份旧数据与目标数据，再按策略写入。当前组有账号在线时会被拒绝。</p>
    <label class="field-card">
      <span>新的 data UUID</span>
      <input id="rebind-input" type="text" list="identity-data-uuids" autocomplete="off">
      <small>留空会生成一个新的随机 UUID。</small>
    </label>
    <button type="button" id="rebind-generate" class="ghost">生成新 UUID</button>
    <fieldset class="mode-list">
      <legend>目标 data UUID 已有数据时怎么处理？</legend>
      <label class="mode-card">
        <input type="radio" name="rebind-mode" value="reject" checked>
        <span><strong>停止（推荐）</strong><small>发现目标已有数据就取消，最安全。</small></span>
      </label>
      <label class="mode-card">
        <input type="radio" name="rebind-mode" value="merge">
        <span><strong>保留目标数据</strong><small>目标已有数据时保留目标，只补齐缺失文件；旧数据完整备份。</small></span>
      </label>
      <label class="mode-card">
        <input type="radio" name="rebind-mode" value="overwrite">
        <span><strong>覆盖目标数据</strong><small>用当前组的数据覆盖目标数据；旧数据与目标都会先备份。</small></span>
      </label>
    </fieldset>
    <label class="check-line">
      <input id="rebind-confirm" type="checkbox">
      <span>我已了解会先备份旧数据与目标数据</span>
    </label>
    <div id="rebind-error" class="message error"></div>
    <div class="dialog-actions">
      <span class="spacer"></span>
      <button type="button" id="rebind-cancel" class="ghost">取消</button>
      <button type="button" id="rebind-apply" class="primary">确认重绑</button>
    </div>
  </form>
</dialog>

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

<dialog id="player-dialog" class="app-dialog">
  <form method="dialog" class="dialog-body" id="player-form">
    <h3 id="player-dialog-title">选择玩家</h3>
    <p id="player-dialog-note" class="dialog-sub">从服务器已知玩家中选择；在线玩家优先。</p>
    <label class="search-box">
      <span aria-hidden="true">⌕</span>
      <input id="player-search" type="search" placeholder="搜索玩家名 / UUID">
    </label>
    <div id="player-list" class="player-list"></div>
    <div class="dialog-actions">
      <span class="spacer"></span>
      <button type="button" id="player-cancel" class="ghost">取消</button>
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
