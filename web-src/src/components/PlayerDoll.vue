<template>
  <div class="player-doll" :class="{ offline: !online }" :style="{ height: height + 'px' }">
    <img
      v-if="!bodyFailed"
      class="doll-body"
      :src="bodySrc"
      :alt="name || uuid || '玩家'"
      draggable="false"
      @error="bodyFailed = true"
    />
    <div v-else class="doll-fallback">
      <img
        v-if="!headFailed"
        class="doll-head"
        :src="headSrc"
        :alt="name || uuid || '玩家'"
        draggable="false"
        @error="headFailed = true"
      />
      <span v-else class="doll-initial">{{ initial }}</span>
      <span class="doll-shadow"></span>
    </div>
    <span class="doll-shadow"></span>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue';

const props = defineProps({
  uuid: { type: String, default: '' },
  name: { type: String, default: '' },
  online: { type: Boolean, default: false },
  height: { type: Number, default: 220 },
});

const bodyFailed = ref(false);
const headFailed = ref(false);
const initial = computed(() => String(props.name || props.uuid || '?').slice(0, 1).toUpperCase());

const query = computed(() => {
  const params = new URLSearchParams();
  if (props.uuid) params.set('uuid', props.uuid);
  if (props.name) params.set('name', props.name);
  return params.toString();
});
const bodySrc = computed(() => '/api/user/map/body?' + query.value);
const headSrc = computed(() => '/api/user/map/avatar?' + query.value);

watch(query, () => {
  bodyFailed.value = false;
  headFailed.value = false;
});
</script>

<style scoped>
.player-doll {
  position: relative;
  display: flex;
  flex: 0 0 auto;
  align-items: flex-end;
  justify-content: center;
  width: 200px;
  min-width: 160px;
  filter: drop-shadow(0 12px 14px rgba(0, 0, 0, .35));
}

.player-doll.offline {
  filter: grayscale(.35) drop-shadow(0 12px 14px rgba(0, 0, 0, .35));
  opacity: .9;
}

.doll-body {
  height: 100%;
  width: auto;
  max-width: 200px;
  object-fit: contain;
  image-rendering: auto;
  user-select: none;
}

.doll-fallback {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  height: 100%;
}

.doll-head {
  width: min(112px, 60%);
  aspect-ratio: 1;
  border-radius: 8px;
  background: #263241;
  image-rendering: pixelated;
}

.doll-initial {
  display: grid;
  place-items: center;
  width: min(112px, 60%);
  aspect-ratio: 1;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background: var(--panel-soft);
  color: var(--fg);
  font-size: 40px;
  font-weight: 800;
}

.doll-shadow {
  position: absolute;
  bottom: -4px;
  left: 50%;
  width: 120px;
  height: 14px;
  transform: translateX(-50%);
  border-radius: 50%;
  background: radial-gradient(ellipse, rgba(0, 0, 0, .45), transparent 70%);
  pointer-events: none;
}
</style>
