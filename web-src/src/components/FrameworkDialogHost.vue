<template>
  <div class="framework-dialog-host">
    <el-dialog
      v-for="dialog in dialogs"
      :key="dialog.id"
      v-model="dialog.visible"
      :title="dialog.title"
      :width="dialog.width"
      append-to-body
      :z-index="1600"
      class="samsara-framework-dialog"
      @opened="mountFrameworkDialog(dialog)"
    >
      <div
        :ref="(el) => setFrameworkDialogHost(dialog.id, el)"
        class="framework-dialog-body"
      ></div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onBeforeUnmount } from 'vue';
import {
  closeAllFrameworkDialogs,
  frameworkDialogs as dialogs,
  mountFrameworkDialog,
  setFrameworkDialogHost,
} from '../composables/useFrameworkDialog.js';

onBeforeUnmount(() => {
  closeAllFrameworkDialogs();
});
</script>

<style>
.samsara-framework-dialog .el-dialog__body {
  max-height: 86vh;
  overflow: auto;
}
.samsara-framework-dialog .framework-dialog-body > .dialog-body > h3 {
  display: none;
}
.samsara-framework-dialog .identity-data-content {
  max-height: none;
}
</style>
