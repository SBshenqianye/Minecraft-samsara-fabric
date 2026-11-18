import { createApp } from 'vue';
import App from './App.vue';
import router from './router.js';
import { ROUTE_ALIASES } from './navigation.js';
import './base.css';
import './tokens.css';
import './style.css';
import './ia.css';
import './shell.css';
import './polish.css';
// Element Plus 组件样式由 unplugin-vue-components 按需注入；
// ElMessage / ElMessageBox 是 API 调用，不走模板解析器，需要显式引入。
import 'element-plus/theme-chalk/base.css';
import 'element-plus/theme-chalk/el-message.css';
import 'element-plus/theme-chalk/el-message-box.css';

const app = createApp(App).use(router);
// 旧 hash 链接（#config、#lottery…）统一跳到规范路径，别名表在 navigation.js。
const alias = ROUTE_ALIASES[window.location.hash.replace(/^#/, '')];
const boot = alias ? router.replace(alias) : Promise.resolve();
boot.finally(() => app.mount('#app'));
