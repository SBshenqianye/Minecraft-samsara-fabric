<template>
  <dialog ref="dialogRef" id="player-dialog" class="player-dialog">
    <div class="player-dialog-head">
      <h3 id="player-dialog-title">{{ title }}</h3>
      <button id="player-cancel" class="ghost" type="button" @click="close">关闭</button>
    </div>
    <label class="search-box">
      <span aria-hidden="true">⌕</span>
      <input id="player-search" v-model="search" type="search" placeholder="搜索玩家名 / UUID" />
    </label>
    <div id="player-list" class="player-list">
      <p v-if="loading" class="muted small">正在读取玩家列表…</p>
      <template v-else>
        <button
          v-for="player in visiblePlayers"
          :key="player.uuid"
          class="player-row"
          type="button"
          @click="choose(player)"
        >
          <strong>{{ player.name }}</strong>
          <span class="muted small">{{ (player.online ? '在线 · ' : '') + (player.uuid || '') }}</span>
          <span class="world-stack-badge" :class="{ ok: player.online }">{{ player.online ? '在线' : '缓存' }}</span>
        </button>
        <p v-if="!visiblePlayers.length" class="muted small">
          没有匹配的已知玩家；可以先让玩家进一次服，或手动填写。
        </p>
      </template>
    </div>
  </dialog>
</template>

<script setup>
import { computed, ref } from 'vue';
import { useApi } from '../../composables/useApi.js';
import { filterPlayers } from './model.js';

const emit = defineEmits(['pick']);

const { request } = useApi();
const dialogRef = ref(null);
const title = ref('选择玩家');
const search = ref('');
const loading = ref(false);
const players = ref([]);
let loaded = false;

const visiblePlayers = computed(() => filterPlayers(players.value, search.value));

async function load() {
  if (loaded) return;
  loading.value = true;
  try {
    const result = await request('/api/server/players');
    players.value = result.ok && Array.isArray(result.data?.players) ? result.data.players : [];
    loaded = true;
  } catch {
    players.value = [];
  } finally {
    loading.value = false;
  }
}

async function open(nextTitle) {
  title.value = nextTitle || '选择玩家';
  search.value = '';
  const dialog = dialogRef.value;
  if (dialog && !dialog.open) dialog.showModal();
  await load();
}

function close() {
  const dialog = dialogRef.value;
  if (dialog?.open) dialog.close();
}

function choose(player) {
  close();
  emit('pick', player);
}

defineExpose({ open, close });
</script>

<style scoped>
.player-dialog { width: min(520px, 92vw); border: 1px solid var(--line); border-radius: 14px; background: var(--panel); color: var(--fg); padding: 14px; }
.player-dialog::backdrop { background: rgba(3, 6, 10, .62); }
.player-dialog-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.player-dialog-head h3 { margin: 0; font-size: 15px; }
.player-list { display: flex; flex-direction: column; gap: 6px; max-height: 52vh; overflow: auto; margin-top: 8px; }
.player-row { display: flex; align-items: center; gap: 10px; width: 100%; padding: 7px 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--panel-soft); color: var(--fg); text-align: left; }
.player-row:hover { border-color: var(--accent); }
</style>
