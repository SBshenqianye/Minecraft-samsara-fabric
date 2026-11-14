<template>
<TabShell name="world-stack">
<div id="panel-world-stack" class="panel-stack">
  <section class="panel" id="world-stack-overview">
    <div class="panel-head">
      <div>
        <h2>世界栈</h2>
        <p class="panel-sub">导入服务器本地 world 目录；导入/归档在服务器重启时执行，源目录只读保持不变。排序只改逻辑顺序，不重命名 stack_<span class="muted">n</span> 目录。</p>
      </div>
      <div class="panel-tools">
        <button id="world-stack-timeline-open" class="ghost" type="button">操作时间线</button>
        <button id="world-stack-refresh" class="ghost" type="button">重新读取</button>
      </div>
    </div>
    <div id="world-stack-message" class="message" role="status"></div>
    <div id="world-stack-stats" class="stat-row"></div>
  </section>

  <section class="panel" id="world-stack-layers">
    <div class="panel-head">
      <div>
        <h2>栈层</h2>
        <p class="panel-sub">world 固定是栈顶；旧栈层是 world 内部的自定义维度，按从栈底往上数（stack_0 是最下层）。卡片显示存档名、存档创建/最后游玩时间、版本、种子、出生点、维度和数据大小。</p>
      </div>
    </div>
    <div id="world-stack-list" class="world-stack-list"></div>
  </section>

  <section class="panel" id="world-stack-order-panel">
    <div class="panel-head">
      <div>
        <h2>栈位排序</h2>
        <p class="panel-sub">列表从上到下 = 栈顶 → 栈底（world 在最上面）。拖动或上下移动，保存前先看预览。只调整栈顶以下时立即生效；把旧栈层拖到栈顶会交换 world 与该层数据，需重启并在失败时回滚。</p>
      </div>
      <div class="panel-tools world-stack-order-actions">
        <span id="world-stack-order-preview" class="muted small"></span>
        <button id="world-stack-order-save" class="primary" type="button" disabled>保存排序</button>
      </div>
    </div>
    <div id="world-stack-order" class="world-stack-order"></div>
  </section>

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
        <input id="world-stack-import-path" type="text" placeholder="例如 C:\Games\...\saves\world 或服务器根下的目录">
      </label>
      <label class="inline-field">
        <span>位置</span>
        <select id="world-stack-import-position">
          <option value="bottom">栈底</option>
          <option value="between">某两层之间</option>
          <option value="top">栈顶（替换当前 world）</option>
        </select>
      </label>
      <label class="inline-field" id="world-stack-after-wrap" hidden>
        <span>插入位置</span>
        <select id="world-stack-import-after"></select>
      </label>
      <button id="world-stack-import-preview-button" class="ghost" type="button">预览导入</button>
    </div>
    <div id="world-stack-import-error" class="world-stack-error" role="alert" hidden></div>
    <div id="world-stack-import-queue" class="world-stack-queue" hidden></div>
    <div id="world-stack-import-preview" class="world-stack-preview" hidden></div>
  </section>

  <section class="panel" id="world-stack-jobs-panel">
    <div class="panel-head">
      <div>
        <h2>任务与结果</h2>
        <p class="panel-sub">导入/归档是重启时执行的任务；这里显示排队、完成、失败与回滚说明。</p>
      </div>
    </div>
    <div id="world-stack-jobs" class="world-stack-jobs"></div>
  </section>
</div>
</TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
import { useLegacyPage } from '../composables/useLegacyPage.js';

defineOptions({ name: 'WorldStackPage' });

useLegacyPage({
  panelId: 'panel-world-stack',
  load: () => import('../legacy-core/world-stack.js'),
  activate: () => window.SamsaraWorldStack?.activate?.(),
});
</script>
