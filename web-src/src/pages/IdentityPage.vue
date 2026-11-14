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
        <button id="identity-add-group" class="ghost" type="button">新增组</button>
        <button id="identity-audit" class="ghost" type="button">审计</button>
      </div>
    </div>
    <div id="identity-message" class="message" role="status"></div>
    <div id="identity-path" class="path-line">{{ pathLine }}</div>
    <div id="identity-root" class="path-line">{{ rootLine }}</div>

    <!-- 设置卡片由 Vue 接管；内核 renderSettings() 通过 __samsaraIdentityVueSettings 跳过。 -->
    <div class="settings-grid">
      <div class="switch-card">
        <span class="switch-text">
          <strong>启用身份组</strong>
          <small>关闭时所有账号按自身 UUID 处理，不做数据同步。</small>
        </span>
        <span id="identity-enabled" class="segmented-host">
          <SegmentedButtons
            :value="settings.enabled ? 'true' : 'false'"
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
            :value="settings.sync ? 'true' : 'false'"
            :choices="SYNC_CHOICES"
            @change="(next) => setSetting('sync', next === 'true')"
          />
        </span>
      </div>
      <label class="field-card">
        <span>宠物主人上限</span>
        <input
          id="identity-max-owners"
          :value="settings.maxOwners"
          type="number"
          min="1"
          max="1024"
          step="1"
          @input="(event) => setSetting('maxOwners', Number(event.target.value || 32))"
        >
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

    <div id="identity-groups" class="identity-groups"></div>

    <div class="pool-panel" id="identity-pool-panel">
      <div class="panel-head compact">
        <div>
          <h3>未进组 / 数据 UUID 库</h3>
          <p class="panel-sub">账号和 data 分开显示：正版账号、离线账号、data 各自独立。把账号拖进卡片的“正版账号/离线账号”区域，把 data UUID 拖进“数据”区域。正版账号/离线账号只能在一个组；data 可以复制到多个组。</p>
        </div>
        <div class="panel-tools">
          <label class="inline-field">
            <span>名称 / UUID</span>
            <input id="identity-resolve-input" type="text" placeholder="输入玩家名或 UUID" autocomplete="off">
          </label>
          <button id="identity-resolve-button" class="ghost" type="button">解析</button>
          <button id="identity-generate" class="ghost" type="button">生成 data UUID</button>
        </div>
      </div>
      <div id="identity-resolve-result" class="resolve-result" hidden></div>
      <div class="pool-filter-bar">
        <label class="inline-field">
          <span>筛选</span>
          <input id="identity-pool-search" type="search" placeholder="名称 / UUID" autocomplete="off">
        </label>
        <label class="check-line">
          <input id="identity-hide-probe" type="checkbox" checked>
          <span>隐藏疑似 probe / 测试 ID</span>
        </label>
        <label class="check-line">
          <input id="identity-hide-unknown" type="checkbox" checked>
          <span>隐藏占位名（玩家-xxxxxxxx）</span>
        </label>
        <span id="identity-pool-count" class="muted small"></span>
      </div>
      <div id="identity-suggestions" class="identity-suggestions" hidden></div>
      <details class="pool-section" id="identity-premium-section" open>
        <summary>正版账号 UUID（未进组） <span id="identity-premium-count" class="muted small"></span></summary>
        <div id="identity-premium-pool" class="chip-pool"></div>
      </details>
      <details class="pool-section" id="identity-offline-section" open>
        <summary>离线账号 UUID（未进组） <span id="identity-offline-count" class="muted small"></span></summary>
        <div id="identity-offline-pool" class="chip-pool"></div>
      </details>
      <details class="pool-section" id="identity-data-section">
        <summary>数据 UUID（data） <span id="identity-data-count" class="muted small"></span></summary>
        <div id="identity-data-pool" class="chip-pool"></div>
      </details>
    </div>

    <details class="audit-card" id="identity-audit-card" hidden>
      <summary>审计结果</summary>
      <pre id="identity-audit-output" class="audit-output"></pre>
    </details>
  </section>
</div>
</TabShell>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import TabShell from '../components/TabShell.vue';
import SegmentedButtons from '../components/SegmentedButtons.vue';
import { useLegacyPage } from '../composables/useLegacyPage.js';
import { normalizeSettings } from './identity/model.js';

defineOptions({ name: 'IdentityPage' });

const ENABLED_CHOICES = [{ value: 'true', label: '启用' }, { value: 'false', label: '关闭' }];
const SYNC_CHOICES = [{ value: 'true', label: '启用' }, { value: 'false', label: '关闭' }];

const model = ref(null);
const settings = reactive(normalizeSettings());
const pathLine = computed(() => '配置文件：' + (model.value?.path || '—'));
const rootLine = computed(() => '运行时数据：' + (model.value?.identityRoot || '—'));

// 设置卡片由 Vue 渲染，通知内核跳过 renderSettings()。
window.__samsaraIdentityVueSettings = true;

function applyModel(next) {
  model.value = next || null;
  Object.assign(settings, normalizeSettings(next?.config));
}

function onModelEvent(event) {
  applyModel(event.detail?.model);
}

function setSetting(key, value) {
  settings[key] = value;
  window.SamsaraIdentity?.setSettings?.({ [key]: value });
}

onMounted(() => {
  window.addEventListener('samsara:identity-model', onModelEvent);
});

onBeforeUnmount(() => {
  window.removeEventListener('samsara:identity-model', onModelEvent);
  window.__samsaraIdentityVueSettings = false;
});

useLegacyPage({
  panelId: 'panel-identity',
  load: () => import('../legacy-core/identity.js'),
  activate: () => {
    window.SamsaraIdentity?.activate?.();
    applyModel(window.SamsaraIdentity?.getModel?.() || model.value);
  },
});
</script>
