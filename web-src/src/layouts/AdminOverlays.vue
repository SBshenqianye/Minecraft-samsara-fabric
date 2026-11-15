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

<dialog ref="dialogRef" id="token-dialog" class="app-dialog token-dialog">
  <form method="dialog" class="dialog-body" id="token-form" @submit.prevent="submit">
    <h3>输入访问 token</h3>
    <p class="dialog-sub">token 在服务器启动日志或 <code>config/samsara/web.yml</code> 里，只需填写一次；之后保存在当前浏览器会话中，不再显示。</p>
    <label class="field-card">
      <span>访问 token</span>
      <input id="token-input" v-model="tokenInput" type="password" placeholder="粘贴 web.yml 里的 token" autocomplete="off">
      <small>只存 sessionStorage，不写 URL，也不进日志。</small>
    </label>
    <div id="token-error" class="message error" role="status">{{ error }}</div>
    <div class="dialog-actions">
      <span class="spacer"></span>
      <button type="button" id="token-connect" class="primary" :disabled="connecting" @click="submit">{{ connecting ? '连接中…' : '连接' }}</button>
    </div>
  </form>
</dialog>

<datalist id="identity-candidates"></datalist>
<datalist id="identity-data-uuids"></datalist>

<button id="back-to-top" class="back-to-top" type="button" title="回到顶部" hidden>↑</button>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { connect, session } from '../composables/useSession.js';
import { getToken } from '../composables/useApi.js';

defineOptions({ name: 'AdminOverlays' });

const dialogRef = ref(null);
const tokenInput = ref(getToken());
const error = ref('');
const connecting = ref(false);

function openTokenDialog() {
  const dialog = dialogRef.value;
  if (!dialog || dialog.open) return;
  tokenInput.value = getToken() || tokenInput.value;
  error.value = '';
  if (typeof dialog.showModal === 'function') dialog.showModal();
}

function closeTokenDialog() {
  const dialog = dialogRef.value;
  if (dialog?.open) dialog.close();
}

async function submit() {
  if (connecting.value) return;
  connecting.value = true;
  error.value = '';
  const input = document.getElementById('token-input');
  const value = String(tokenInput.value || input?.value || '');
  const ok = await connect(value);
  connecting.value = false;
  if (!ok) {
    error.value = session.error || '连接失败';
    return;
  }
  closeTokenDialog();
}

function onNeedToken() {
  nextTick(openTokenDialog);
}

watch(() => session.connected, (connected) => {
  if (connected) closeTokenDialog();
});

onMounted(() => {
  window.addEventListener('samsara:need-token', onNeedToken);
});

onBeforeUnmount(() => {
  window.removeEventListener('samsara:need-token', onNeedToken);
});
</script>
