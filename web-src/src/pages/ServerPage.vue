<template>
<div id="panel-server">
<div id="server-config-panel" class="server-panel">
    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>服务器配置</h2>
          <p class="panel-sub">6 个服务器文件在同一页显示，可折叠、可搜索；写入前自动备份到 samsara/server-config-backups。server.properties/eula 需重启生效，其余保存后立即重载。</p>
        </div>
        <div class="panel-tools">
          <label class="search-box">
            <span aria-hidden="true">⌕</span>
            <input id="server-config-search" type="search" placeholder="搜索配置项：键名 / 说明">
          </label>
        </div>
      </div>
      <div id="server-config-message" class="message" role="status"></div>
      <div id="server-config-files" class="config-files"></div>
    </section>
  </div>
<div id="server-backup-panel" class="server-panel" hidden>
    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>存档备份</h2>
          <p class="panel-sub">备份 world + 服务器配置 + config/samsara。列表里的“时间线头节点”只是最新一份备份，不是实时世界；当前世界始终在运行中变化，不会出现在备份列表里。每份备份都可以回滚；创建与恢复都在停服后、下次启动前执行，恢复前会自动保留当前 world，失败回滚。</p>
        </div>
      </div>
      <div id="server-backup-message" class="message" role="status"></div>
      <div id="server-backup-stats" class="stat-row"></div>
      <div id="server-backup-pending" class="server-backup-pending" hidden></div>
      <div id="server-backup-preview" class="server-backup-preview" hidden></div>
      <div id="server-backup-timeline" class="backup-timeline"></div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>任务与回滚</h2>
          <p class="panel-sub">创建/恢复任务报告；恢复前的当前世界会保留在 samsara/server-backups/pre-restore-&lt;job&gt;/world。</p>
        </div>
      </div>
      <div id="server-job-list" class="server-job-list"></div>
    </section>

    <section class="panel" id="world-stack-files-panel">
      <div class="panel-head">
        <div>
          <h2>文件位置</h2>
          <p class="panel-sub">动态列出本模组创建/使用的服务器级目录。备份不随存档移动；缓存目录可安全删除；<code>world-stack-backup</code> 只保留最近 N 份（由 <code>backupKeep</code> 控制），<code>world-stack-archive</code> 是用户数据不会自动删除。点击行或 ⧉ 可复制绝对路径。</p>
        </div>
      </div>
      <div id="world-stack-files" class="world-stack-files"></div>
    </section>
  </div>
<div id="server-audit-panel" class="server-panel" hidden>
    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>Web 审计（全部管理端写请求）</h2>
          <p class="panel-sub">覆盖 samsara 配置、其他模组配置、服务器本体文件与 RCON/命令类写请求：每次管理端写操作前自动快照可回滚的小文件（<code>config/samsara/</code>、服务器配置文件、栈层图标与 pending 队列），并记录发起者。可直接回滚配置类修改；世界数据请使用世界栈时间线或本体备份。</p>
        </div>
        <div class="panel-tools">
          <button id="server-audit-refresh" class="ghost" type="button">刷新审计</button>
        </div>
      </div>
      <div id="server-audit-message" class="message" role="status"></div>
      <div id="server-audit-list" class="server-audit-list"></div>
    </section>
  </div>
</div>
</template>

<script setup>
import { useRoute } from 'vue-router';
import { useLegacyPage } from '../composables/useLegacyPage.js';

defineOptions({ name: 'ServerPage' });

const route = useRoute();

useLegacyPage({
  panelIds: ['server-config-panel', 'server-backup-panel', 'server-audit-panel'],
  load: async () => {
    await import('../../../mod/src/main/resources/web/world-stack.js');
    await import('../../../mod/src/main/resources/web/server.js');
  },
  activate: () => window.SamsaraServer?.showPanel?.(route.meta.serverPanel || 'server-config-panel'),
});
</script>
