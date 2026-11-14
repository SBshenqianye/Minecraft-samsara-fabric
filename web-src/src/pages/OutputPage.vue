<template>
  <TabShell name="output">
    <div id="panel-output" class="panel-stack">
      <section class="panel output-panel">
        <div class="panel-head">
          <div>
            <h2>输出格式</h2>
            <p class="panel-sub">
              每个消息是一块可编辑画布；文字、变量标签、按钮标签都在同一个内编辑区。
            </p>
            <p class="panel-sub">
              变量按变量权限节点渲染；按钮按权限节点判断可见性。
            </p>
          </div>
          <div class="panel-tools">
            <label class="search-box">
              <span aria-hidden="true">⌕</span>
              <input id="output-search" type="search" placeholder="搜索消息 / 分组 / 说明" />
            </label>
            <span class="inline-toggle">
              <span class="muted small">自动续行前缀</span>
              <span id="output-auto-continue"></span>
            </span>
          </div>
        </div>

        <div id="output-message" class="message" role="status"></div>

        <div class="output-grid">
          <section class="output-edit-wrap">
            <div class="column-title">字段改写</div>
            <div id="output-disabled" class="output-disabled" hidden></div>
            <div id="output-meta" class="output-meta"></div>
            <div
              id="output-editor"
              class="rich-editor"
              contenteditable="true"
              spellcheck="false"
              aria-label="输出消息编辑画布"
            ></div>
            <div id="output-errors" class="output-errors"></div>
            <div class="editor-hint">
              拖入按钮和变量是原子标签，不能改文字；按钮可以改标签文字。
              选择消息后会恢复原始模板；重置会恢复系统模板。
            </div>
            <h3 class="palette-title">变量 <span id="output-var-count" class="count-pill">0</span></h3>
            <div id="output-palette" class="palette"></div>
            <h3 class="palette-title">按钮 <span id="output-button-count" class="count-pill">0</span></h3>
            <div id="output-buttons" class="palette"></div>
          </section>

          <section class="output-preview-wrap">
            <div class="column-title">渲染预览</div>
            <div id="output-preview" class="output-preview"></div>
            <p class="muted small">预览由服务端渲染；按钮只显示标签，不执行命令。</p>
          </section>
        </div>
      </section>
    </div>
  </TabShell>
</template>

<script setup>
import { onMounted } from 'vue';
import TabShell from '../components/TabShell.vue';
import { waitForElement } from '../legacy/document.js';

defineOptions({ name: 'OutputPage' });

onMounted(async () => {
  // 输出编辑器仍是内容型内核；本页负责挂载，脚本按页加载而不是进全局 admin.js。
  await waitForElement('output-editor', 5000).catch(() => {});
  await import('../legacy-core/output.js');
  window.SamsaraOutput?.activate?.();
});
</script>
