<template>
<TabShell name="identity">
<div id="panel-identity" class="panel-stack">
  <section class="panel">
    <div class="panel-head">
      <div>
        <h2>身份组</h2>
        <p class="panel-sub">把多个账号入口映射到同一份玩家数据。卡片之间可以拖拽组合。账号类型只有两种：<strong>正版账号</strong>（Mojang）和<strong>离线账号</strong>（非正版）；「当前在线」是连接状态，和账号类型无关。</p>
      </div>
      <div class="panel-tools">
        <button id="identity-add-group" class="ghost" type="button" :disabled="identity.loading.value" @click="identity.addGroup">新增组</button>
        <button id="identity-audit" class="ghost" type="button" @click="identity.runAudit">审计</button>
        <button id="identity-reload" class="ghost" type="button" :disabled="identity.loading.value" @click="identity.reload">重新读取</button>
        <button id="identity-save" class="primary" type="button" :disabled="identity.loading.value" @click="identity.save">
          保存并重载{{ identity.dirty.value ? '（未保存）' : '' }}
        </button>
      </div>
    </div>
    <div id="identity-message" class="message" :class="identity.messageKind.value" role="status">{{ identity.message.value }}</div>
    <div id="identity-path" class="path-line">配置文件：{{ identity.model.value?.path || '—' }}</div>
    <div id="identity-root" class="path-line">运行时数据：{{ identity.model.value?.identityRoot || '—' }}</div>

    <div class="settings-grid">
      <div class="switch-card">
        <span class="switch-text">
          <strong>启用身份组</strong>
          <small>关闭时所有账号按自身 UUID 处理，不做数据同步。</small>
        </span>
        <span id="identity-enabled" class="segmented-host">
          <SegmentedButtons
            :value="identity.settings.enabled ? 'true' : 'false'"
            :choices="ENABLED_CHOICES"
            @change="(next) => setSetting('enabled', next === 'true')"
          />
        </span>
      </div>
      <div class="switch-card">
        <span class="switch-text">
          <strong>切换账号时同步玩家数据</strong>
          <small>开启后，同组账号共享背包、位置、进度与统计。</small>
        </span>
        <span id="identity-sync" class="segmented-host">
          <SegmentedButtons
            :value="identity.settings.sync ? 'true' : 'false'"
            :choices="SYNC_CHOICES"
            @change="(next) => setSetting('sync', next === 'true')"
          />
        </span>
      </div>
      <label class="field-card">
        <span>宠物主人上限</span>
        <input
          id="identity-max-owners"
          :value="identity.settings.maxOwners"
          type="number"
          min="1"
          max="1024"
          step="1"
          @input="(event) => setSetting('maxOwners', Number(event.target.value || 32))"
        />
        <small>每个宠物的 owner 列表最多保留多少个主人。</small>
      </label>
    </div>

    <details class="help-card">
      <summary>data UUID 是什么？为什么它和账号 UUID 不一样？</summary>
      <div class="help-body">
        <p><strong>账号 UUID</strong> 是玩家登录时使用的身份：正版账号的 UUID 由 Mojang 分配，离线账号的 UUID 由“OfflinePlayer:名称”计算。一个组最多各放一个正版账号/离线账号入口。身份组按 UUID 匹配，不按名字；同一个名字的正版账号 UUID 和离线账号 UUID 是两个不同的值，所以离线账号 UUID 不能拖进正版账号区域，正版账号 UUID 也不能拖进离线账号区域。</p>
        <p><strong>data UUID</strong> 是玩家数据的存储钥匙，对应 <code>identity/playerdata/&lt;data&gt;.dat</code>。它不是登录账号，所以同一把 data UUID 可以被多个组复用；复用后这些组会共享同一份背包/位置/进度。</p>
        <p>“生成 data UUID”得到的是<strong>空白钥匙</strong>：还没有绑定任何账号，所以池子里显示为“空白 data”。把正版账号/离线账号映射进组、玩家上线并同步后，数据才会写进这把钥匙。池子里的“正版账号数据 / 离线账号数据”候选是用账号 UUID 当数据键，名字直接来自账号，避免出现不知道是谁的 data。</p>
        <p>已有组修改 data UUID 时，会自动弹出重绑确认：程序先备份旧数据与目标数据，再按你选择的策略复制或保留，避免玩家进度丢失。新组没有旧数据，直接设置即可。</p>
      </div>
    </details>

    <div id="identity-groups" class="identity-groups">
      <IdentityGroupCard
        v-for="(group, index) in identity.groups.value"
        :key="group._id"
        :identity="identity"
        :group="group"
        :index="index"
      />
      <div v-if="!identity.groups.value.length" class="panel-sub">
        还没有身份组。点右上角“新增组”创建一张卡片。
      </div>
    </div>

    <IdentityPool :identity="identity" />

    <details class="audit-card" id="identity-audit-card" :hidden="!identity.audit.visible" :open="identity.audit.visible">
      <summary>审计结果</summary>
      <pre id="identity-audit-output" class="audit-output">{{ identity.audit.text }}</pre>
    </details>
  </section>

  <IdentityDialogs :identity="identity" />
</div>
</TabShell>
</template>

<script setup>
import { onBeforeUnmount, onMounted, watch } from 'vue';
import TabShell from '../components/TabShell.vue';
import SegmentedButtons from '../components/SegmentedButtons.vue';
import IdentityDialogs from './identity/IdentityDialogs.vue';
import IdentityGroupCard from './identity/IdentityGroupCard.vue';
import IdentityPool from './identity/IdentityPool.vue';
import { useIdentity } from './identity/useIdentity.js';
import { clearPageNav } from '../composables/usePageNav.js';

defineOptions({ name: 'IdentityPage' });

const ENABLED_CHOICES = [{ value: 'true', label: '启用' }, { value: 'false', label: '关闭' }];
const SYNC_CHOICES = [{ value: 'true', label: '启用' }, { value: 'false', label: '关闭' }];

const identity = useIdentity();

function setSetting(key, value) {
  identity.settings[key] = value;
  identity.markDirty();
}

function onConnected() {
  identity.load();
}

watch(
  () => [identity.groups.value, identity.names, identity.labels, identity.dataList.value],
  () => identity.syncNav(),
  { deep: true, immediate: true },
);

onMounted(() => {
  window.addEventListener('samsara:connected', onConnected);
  identity.load();
});

onBeforeUnmount(() => {
  window.removeEventListener('samsara:connected', onConnected);
  clearPageNav();
});
</script>
