<template>
  <dialog ref="uuidRef" id="uuid-dialog" class="app-dialog">
    <form method="dialog" class="dialog-body" @submit.prevent="identity.applyUuid()">
      <h3 id="uuid-title">{{ identity.uuidDialog.title }}</h3>
      <p id="uuid-desc" class="dialog-sub">
        {{ identity.uuidDialog.role === 'data'
          ? 'data UUID 是玩家数据的钥匙；同一个值可以被多个组复用。已有组改 data 会自动弹出重绑确认。'
          : '正版账号/离线账号入口全局只能属于一个组；拖入别组已有的入口会把它移动过来。' }}
      </p>
      <label class="field-card">
        <span>名称 / UUID</span>
        <input
          id="uuid-input"
          :value="identity.uuidDialog.input"
          type="text"
          list="identity-candidates"
          autocomplete="off"
          placeholder="输入玩家名或完整 UUID"
          @input="onInput($event.target.value)"
        />
        <small>支持玩家名模糊解析，也支持直接粘贴完整 UUID。</small>
      </label>
      <div v-if="identity.uuidDialog.result" id="uuid-resolved" class="resolve-result">
        <button
          v-for="row in resolvedRows"
          :key="row.uuid"
          type="button"
          class="uuid-chip"
          :class="{ selected: identity.uuidDialog.selected === row.uuid, mismatch: row.mismatch }"
          :title="row.mismatch ? row.mismatchText : ''"
          @click="selectUuid(row)"
        >
          <span class="uuid-name">{{ row.label + ' · ' + (row.name || '未命名') }}</span>
          <span class="uuid-code">{{ shortUuid(row.uuid) }}</span>
          <span v-if="row.type" class="uuid-role">{{ row.typeLabel }} UUID</span>
        </button>
        <span v-if="identity.uuidDialog.result.premiumNote" class="note">
          {{ identity.uuidDialog.result.premiumNote }}
        </span>
      </div>
      <div v-else id="uuid-resolved" class="resolve-result" hidden></div>
      <div id="uuid-error" class="message error">{{ identity.uuidDialog.error }}</div>
      <div class="dialog-actions">
        <button v-if="identity.uuidDialog.role !== 'data'" type="button" id="uuid-clear"
            class="ghost danger" @click="identity.clearUuid()">清空当前值</button>
        <span class="spacer"></span>
        <button type="button" id="uuid-cancel" class="ghost" @click="close('uuid')">取消</button>
        <button type="button" id="uuid-apply" class="primary" @click="identity.applyUuid()">确认</button>
      </div>
    </form>
  </dialog>

  <dialog ref="assignRef" id="assign-dialog" class="app-dialog">
    <form method="dialog" class="dialog-body" @submit.prevent="identity.applyAssign()">
      <h3>放入身份组</h3>
      <p id="assign-target" class="dialog-sub">
        {{ (identity.assign.name || shortUuid(identity.assign.uuid)) + ' · ' + identity.assign.uuid }}
      </p>
      <label class="field-card">
        <span>目标组</span>
        <select id="assign-group" :value="identity.assign.groupId"
            @change="identity.setAssignField('groupId', $event.target.value)">
          <option v-for="group in identity.groups.value" :key="group._id" :value="group._id">
            {{ (group.name || '未命名') + (group.data ? '' : '（未设 data）') }}
          </option>
        </select>
      </label>
      <label class="field-card">
        <span>放入区域</span>
        <select id="assign-role" :value="identity.assign.role"
            @change="identity.setAssignField('role', $event.target.value)">
          <option value="data" :disabled="identity.assign.type && identity.assign.type !== 'data'">数据 UUID</option>
          <option value="premium" :disabled="identity.assign.type === 'offline'">正版账号 UUID</option>
          <option value="offline" :disabled="identity.assign.type === 'premium'">离线账号 UUID</option>
        </select>
      </label>
      <div id="assign-error" class="message error">{{ identity.assign.error }}</div>
      <div class="dialog-actions">
        <span class="spacer"></span>
        <button type="button" id="assign-cancel" class="ghost" @click="close('assign')">取消</button>
        <button type="button" id="assign-apply" class="primary" @click="identity.applyAssign()">确认</button>
      </div>
    </form>
  </dialog>

  <dialog ref="rebindRef" id="rebind-dialog" class="app-dialog">
    <form method="dialog" class="dialog-body" @submit.prevent="identity.applyRebind()">
      <h3>重绑 data UUID</h3>
      <p class="dialog-sub">组：<strong id="rebind-group">{{ identity.rebind.groupName }}</strong></p>
      <p class="dialog-note">data UUID 是玩家数据的存储钥匙。重绑不会删除旧数据；程序会先备份旧数据与目标数据，再按你选择的策略写入。</p>
      <label class="field-card">
        <span>新的 data UUID</span>
        <input id="rebind-input" :value="identity.rebind.newData" type="text" list="identity-data-uuids" autocomplete="off"
            @input="identity.setRebindField('newData', $event.target.value)" />
        <small>留空时确认会生成一把新的空白 data UUID。</small>
      </label>
      <button type="button" id="rebind-generate" class="ghost" @click="generate">生成 data UUID</button>
      <fieldset class="mode-cards">
        <legend>已有数据时</legend>
        <label class="mode-card">
          <input :checked="identity.rebind.mode === 'reject'" type="radio" name="rebind-mode" value="reject"
              @change="identity.setRebindField('mode', 'reject')" />
          <span><strong>停止（推荐）</strong><small>发现目标已有数据就取消，最安全。</small></span>
        </label>
        <label class="mode-card">
          <input :checked="identity.rebind.mode === 'merge'" type="radio" name="rebind-mode" value="merge"
              @change="identity.setRebindField('mode', 'merge')" />
          <span><strong>保留目标数据</strong><small>目标已有数据时保留目标，只补齐缺失文件；旧数据完整备份。</small></span>
        </label>
        <label class="mode-card">
          <input :checked="identity.rebind.mode === 'overwrite'" type="radio" name="rebind-mode" value="overwrite"
              @change="identity.setRebindField('mode', 'overwrite')" />
          <span><strong>覆盖目标数据</strong><small>用当前组的数据覆盖目标数据；旧数据与目标都会先备份。</small></span>
        </label>
      </fieldset>
      <label class="check-line">
        <input id="rebind-confirm" :checked="identity.rebind.confirm" type="checkbox"
            @change="identity.setRebindField('confirm', $event.target.checked)" />
        <span>我已了解会先备份旧数据与目标数据</span>
      </label>
      <div id="rebind-error" class="message error">{{ identity.rebind.error }}</div>
      <div class="dialog-actions">
        <span class="spacer"></span>
        <button type="button" id="rebind-cancel" class="ghost" @click="close('rebind')">取消</button>
        <button type="button" id="rebind-apply" class="primary" @click="identity.applyRebind()">确认重绑</button>
      </div>
    </form>
  </dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import { shortUuid } from './model.js';

const props = defineProps({
  identity: { type: Object, required: true },
});

const uuidRef = ref(null);
const assignRef = ref(null);
const rebindRef = ref(null);

const resolvedRows = computed(() => {
  const result = props.identity.uuidDialog.result;
  if (!result) return [];
  const role = props.identity.uuidDialog.role;
  const rows = [];
  const push = (label, uuid, name, type) => {
    if (!uuid) return;
    const mismatch = (role === 'premium' && type === 'offline')
      || (role === 'offline' && type === 'premium');
    rows.push({
      uuid,
      label,
      name,
      type,
      typeLabel: type === 'offline' ? '离线账号' : type === 'premium' ? '正版账号' : '',
      mismatch,
      mismatchText: mismatch ? '类型不匹配，不能放进这个区域' : '',
    });
  };
  push('正版账号', result.premium, result.name, 'premium');
  push('离线账号', result.offline, result.name, 'offline');
  for (const [uuid, name] of Object.entries(result.names || {})) {
    if (uuid === result.premium || uuid === result.offline) continue;
    const type = props.identity.types[uuid] || '';
    push('账号', uuid, name, type);
  }
  return rows;
});

let timer = 0;
function onInput(value) {
  props.identity.setUuidInput(value);
  window.clearTimeout(timer);
  timer = window.setTimeout(() => {
    props.identity.resolveUuidDialog(props.identity.uuidDialog.input);
  }, 220);
}

function selectUuid(row) {
  if (row.mismatch) {
    props.identity.setUuidError(row.mismatchText);
    return;
  }
  props.identity.selectUuid(row.uuid);
}

async function generate() {
  const uuid = await props.identity.generate(false);
  if (uuid) props.identity.setRebindField('newData', uuid);
}

function open(dialog, openState) {
  if (openState && dialog && !dialog.open) {
    if (typeof dialog.showModal === 'function') dialog.showModal();
  } else if (!openState && dialog?.open) {
    dialog.close();
  }
}

watch(() => props.identity.uuidDialog.open, () => open(uuidRef.value, props.identity.uuidDialog.open));
watch(() => props.identity.assign.open, () => open(assignRef.value, props.identity.assign.open));
watch(() => props.identity.rebind.open, () => open(rebindRef.value, props.identity.rebind.open));

function close(which) {
  props.identity.closeDialog(which);
  open(which === 'uuid' ? uuidRef.value : which === 'assign' ? assignRef.value : rebindRef.value, false);
}
</script>
