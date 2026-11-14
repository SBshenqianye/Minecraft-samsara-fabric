<template>
  <article :id="'identity-group-' + group._id" class="identity-card">
    <div class="card-head">
      <div class="card-title-row">
        <img
          v-if="skin.uuid || skin.name"
          class="identity-avatar"
          :src="avatarSrc"
          alt=""
          loading="lazy"
          :title="group.name || '身份组头像'"
          @error="avatarFailed = true"
        />
        <span v-else class="identity-avatar" aria-hidden="true"></span>
        <span class="group-index">{{ String(index + 1).padStart(2, '0') }}</span>
        <input
          class="group-name"
          :value="group.name"
          placeholder="组名（必填）"
          maxlength="64"
          @input="(event) => identity.updateGroup(group, { name: event.target.value })"
        />
      </div>
      <div class="card-badges">
        <span v-if="view.shared > 1" class="perm-badge default">共享 data</span>
        <span v-if="view.syncBlocked" class="perm-badge danger-text">同步阻断</span>
        <span v-if="view.online" class="perm-badge default">当前在线</span>
      </div>
    </div>

    <label class="card-note">
      <span>备注</span>
      <input
        type="text"
        :value="group.note || ''"
        maxlength="512"
        placeholder="例如：正版账号 + 小号"
        @input="(event) => identity.updateGroup(group, { note: event.target.value })"
      />
    </label>

    <div class="uuid-zones">
      <div
        v-for="zone in zones"
        :key="zone.role"
        class="uuid-zone"
        :class="{ 'drop-over': dropRole === zone.role }"
        :data-group-id="group._id"
        :data-role="zone.role"
        @dragover.prevent="dropRole = zone.role"
        @dragleave="onDragLeave(zone.role, $event)"
        @drop.prevent="onDrop(zone.role)"
      >
        <div class="zone-label">
          <span>{{ zone.label }}</span>
          <span class="hint">{{ zoneHint(zone) }}</span>
          <button class="chip-action" type="button" title="按名称或 UUID 选择"
              @click="identity.openUuid(group, zone.role)">选择</button>
          <button class="chip-action" type="button" title="从剪贴板读取 UUID 放到这个区域"
              @click="identity.pasteIntoZone(group._id, zone.role)">粘贴</button>
        </div>
        <div class="zone-body">
          <IdentityChip
            v-if="group[zone.role]"
            :uuid="group[zone.role]"
            :role="zone.role"
            :name="nameOf(group[zone.role])"
            :display-name="displayOf(group[zone.role], zone.role)"
            :tag="zone.role === 'data' ? dataTag(group[zone.role]) : ''"
            :online="Boolean(identity.online[group[zone.role]])"
            :online-label="Boolean(identity.online[group[zone.role]])"
            :locked="zone.role === 'data' && Boolean(identity.labels[group[zone.role]]?.name)"
            :title="zone.label"
            :actions="chipActions(zone.role)"
            @dragstart="(payload) => identity.startDrag({ ...payload, groupId: group._id, role: zone.role })"
            @dragend="identity.endDrag"
            @action="(action) => onChipAction(action, zone.role)"
          />
          <span v-else class="zone-empty" @click="identity.openUuid(group, zone.role)">
            拖入账号 / data UUID，或点击这里选择
          </span>
        </div>
      </div>
    </div>

    <div class="card-actions">
      <button class="ghost" type="button" title="查看该身份组 data UUID 的背包、末影箱、成就、统计与备份"
          :disabled="!group.data" @click="openDetail">数据详情</button>
      <button class="ghost" type="button" title="备份该身份组 data UUID 的玩家数据、成就与统计"
          :disabled="!group.data" @click="identity.backupData(group.data)">备份数据</button>
      <button class="ghost" type="button"
          title="直接编辑该身份组 data UUID 的 41 格背包（大窗口，写入前自动备份）"
          @click="identity.openInventory(group)">编辑身份组背包</button>
      <button class="ghost" type="button" @click="preview">预览影响</button>
      <button class="ghost danger" type="button" @click="identity.removeGroup(group)">删除组</button>
    </div>
    <pre ref="previewOutput" class="card-preview" hidden></pre>
  </article>
</template>

<script setup>
import { computed, ref } from 'vue';
import IdentityChip from './IdentityChip.vue';
import { dataDisplayName, dataTag as dataTagOf, groupSkin } from './model.js';

const props = defineProps({
  identity: { type: Object, required: true },
  group: { type: Object, required: true },
  index: { type: Number, default: 0 },
});

const avatarFailed = ref(false);
const dropRole = ref('');
const previewOutput = ref(null);

const zones = [
  { role: 'data', label: '数据 UUID', hint: '可共享' },
  { role: 'premium', label: '正版账号 UUID', hint: 'Mojang 账号 · 仅一个组' },
  { role: 'offline', label: '离线账号 UUID', hint: '非正版 · 仅一个组' },
];

const view = computed(() => props.identity.groupDetail(props.group));
const skin = computed(() => groupSkin(props.group, props.identity.names));
const avatarSrc = computed(() => {
  const params = new URLSearchParams();
  if (skin.value.uuid) params.set('uuid', skin.value.uuid);
  if (skin.value.name) params.set('name', skin.value.name);
  return '/api/user/map/head?' + params.toString();
});

function zoneHint(zone) {
  if (zone.role === 'data' && view.value.shared > 1) {
    return zone.hint + ' · ' + view.value.shared + ' 组共用';
  }
  return zone.hint;
}

function nameOf(uuid) {
  return props.identity.names[uuid] || '';
}

function displayOf(uuid, role) {
  const custom = role === 'data'
    ? dataDisplayName(uuid, props.identity.labels, props.identity.dataList.value)
    : '';
  if (custom) return custom;
  return props.identity.names[uuid]
    || (role === 'data'
      ? (props.group.name ? props.group.name + ' · 数据' : '空白 data')
      : '未命名');
}

function dataTag(uuid) {
  const all = [...(props.identity.model?.value?.candidates || [])];
  const found = all.find((item) => item.uuid === uuid);
  return dataTagOf(uuid, found?.type || '');
}

function chipActions(role) {
  if (role === 'data') {
    return [
      { key: 'copy', label: '复制', title: '复制 UUID' },
      { key: 'label', label: '命名', title: '给这个 data UUID 起名/备注' },
      { key: 'detail', label: '详情', title: '查看背包、末影箱、成就与统计' },
      { key: 'backup', label: '备份', title: '备份该 data UUID 的玩家数据' },
      { key: 'clear', label: '清空', title: '清空这个组的数据 UUID；不会删除玩家数据' },
    ];
  }
  return [
    { key: 'copy', label: '复制', title: '复制 UUID' },
    { key: 'clear', label: '移除', title: '从本组移除这个账号入口' },
  ];
}

function onChipAction(action, role) {
  const uuid = props.group[role];
  if (!uuid) return;
  if (action === 'copy') props.identity.copyUuid(uuid);
  if (action === 'label') props.identity.editLabel(uuid);
  if (action === 'detail') openDetail();
  if (action === 'backup') props.identity.backupData(uuid);
  if (action === 'clear') props.identity.clearRole(props.group._id, role);
}

function openDetail() {
  if (props.group.data) props.identity.openDetail(props.group.data);
}

function preview() {
  if (!previewOutput.value) return;
  previewOutput.value.hidden = false;
  props.identity.previewGroup(props.group, (text) => {
    if (previewOutput.value) previewOutput.value.textContent = text;
  });
}

function onDragLeave(role, event) {
  if (!event.currentTarget.contains(event.relatedTarget)) {
    if (dropRole.value === role) dropRole.value = '';
  }
}

function onDrop(role) {
  dropRole.value = '';
  props.identity.dropOn(props.group._id, role);
}
</script>
