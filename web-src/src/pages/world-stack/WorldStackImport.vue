<template>
  <section class="panel" id="world-stack-import-panel">
    <div class="panel-head">
      <div>
        <h2>导入本地世界</h2>
        <p class="panel-sub">只接受服务器本地路径；允许范围 = 服务器根目录 + <code>web.yml</code> 的 <code>importRoots</code>。只复制，不移动/删除源目录。可连续确认多个世界，按队列顺序在下次重启时依次执行，不需要多次重启。</p>
      </div>
    </div>
    <div class="world-stack-form">
      <label class="inline-field world-stack-path">
        <span>源目录</span>
        <input
          id="world-stack-import-path"
          type="text"
          placeholder="例如 C:\Games\...\saves\world 或服务器根下的目录"
          :value="state.path"
          @input="store.setImportField('path', $event.target.value)"
        />
      </label>
      <label class="inline-field">
        <span>位置</span>
        <select
          id="world-stack-import-position"
          :value="state.position"
          @change="store.setImportField('position', $event.target.value)"
        >
          <option value="bottom">栈底</option>
          <option value="between">某两层之间</option>
          <option value="top">栈顶（替换当前 world）</option>
        </select>
      </label>
      <label v-show="state.position === 'between'" class="inline-field" id="world-stack-after-wrap">
        <span>插入位置</span>
        <select
          id="world-stack-import-after"
          :value="state.afterId"
          @change="store.setImportField('afterId', $event.target.value)"
        >
          <option v-for="option in store.afterOptions.value" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
          <option v-if="!store.afterOptions.value.length" value="" disabled>没有可插入的旧栈层</option>
        </select>
      </label>
      <button
        id="world-stack-import-preview-button"
        class="ghost"
        type="button"
        :disabled="state.busy"
        @click="store.previewImport()"
      >{{ state.busy ? '处理中…' : '预览导入' }}</button>
    </div>
    <div id="world-stack-import-error" class="world-stack-error" role="alert" :hidden="!state.error">
      <ul v-if="state.error" class="world-stack-error-list"><li>{{ state.error }}</li></ul>
    </div>
    <div id="world-stack-import-queue" class="world-stack-queue" :hidden="!store.importQueue.value.length">
      <p class="muted small">待导入队列（{{ store.importQueue.value.length }} 项）：重启时按下列顺序依次执行，不需要多次重启。</p>
      <div v-for="item in store.importQueue.value" :key="item.jobId + item.index" class="world-stack-queue-item">
        <strong>{{ item.title }}</strong>
        <span class="grow" :title="item.source">{{ item.label }}</span>
        <span class="muted small">{{ item.size }}</span>
        <button class="ghost danger" type="button" title="取消这条尚未执行的导入" @click="store.cancelImport(item.jobId)">取消</button>
      </div>
    </div>
    <div id="world-stack-import-preview" class="world-stack-preview" :hidden="!preview">
      <template v-if="preview">
        <div class="world-stack-preview-title">{{ preview.title }}</div>
        <dl class="world-stack-rows">
          <template v-for="[key, value] in preview.rows" :key="key">
            <dt>{{ key }}</dt>
            <dd>{{ value }}</dd>
          </template>
        </dl>
        <div v-if="preview.warnings.length" class="world-stack-warning">
          <ul class="world-stack-warning-list">
            <li v-for="(line, index) in preview.warnings" :key="'w' + index">{{ line }}</li>
          </ul>
        </div>
        <div v-if="preview.steps.length" class="world-stack-steps">
          <strong>将执行</strong>
          <ol><li v-for="(step, index) in preview.steps" :key="'s' + index">{{ step }}</li></ol>
        </div>
        <div v-if="preview.errors.length" class="world-stack-error">
          <ul class="world-stack-error-list">
            <li v-for="(line, index) in preview.errors" :key="'e' + index">{{ line }}</li>
          </ul>
        </div>
        <template v-else>
          <label class="check-line world-stack-confirm">
            <input
              type="checkbox"
              :checked="state.confirm"
              @change="store.setImportField('confirm', $event.target.checked)"
            />
            <span>{{ preview.confirmText }}</span>
          </label>
          <button
            class="primary"
            type="button"
            :disabled="!state.confirm || state.busy"
            @click="store.runImport()"
          >{{ state.busy ? '排队中…' : '确认导入（需重启）' }}</button>
        </template>
      </template>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  store: { type: Object, required: true },
});
const state = props.store.importState;
const preview = computed(() => props.store.importPreview.value);
</script>
