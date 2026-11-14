import { createApp } from 'vue';
import App from './App.vue';
import router from './router.js';
import { LEGACY_ALIASES } from './navigation.js';
// 旧命令式面板的组件样式仍来自 legacy 资源；tokens.css 在其后接管 Vue 壳层的设计变量。
import './legacy.css';
import './tokens.css';
import './style.css';
import './ia.css';
import './shell.css';
// Element Plus 组件样式由 unplugin-vue-components 按需注入；
// ElMessage / ElMessageBox 是 API 调用，不走模板解析器，需要显式引入。
import 'element-plus/theme-chalk/base.css';
import 'element-plus/theme-chalk/el-message.css';
import 'element-plus/theme-chalk/el-message-box.css';

window.SamsaraUseRouter = true;
// 原生 RCON 控制台接管后，旧 server.js 的 console 监听在 Vue 应用内短路。
window.SamsaraNativeConsole = true;

const app = createApp(App).use(router);
window.SamsaraRouter = router;
// 旧 hash 链接（#config、#lottery…）统一跳到规范路径，别名表在 navigation.js。
const legacy = LEGACY_ALIASES[window.location.hash.replace(/^#/, '')];
const boot = legacy ? router.replace(legacy) : Promise.resolve();
boot.finally(() => app.mount('#app'));
