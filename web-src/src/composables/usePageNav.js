import { reactive } from 'vue';

/**
 * 页内导航（左侧 #page-nav）的原生状态源。
 * 旧页面仍由 app.js 写 #global-nav；Vue 页面用这里声明标题与锚点，AdminLayout 负责渲染。
 */
export const pageNavState = reactive({
  title: '',
  count: null,
  items: [],
});

export function setPageNav({ title = '', count = null, items = [] } = {}) {
  pageNavState.title = title;
  pageNavState.count = count;
  pageNavState.items = Array.isArray(items) ? items : [];
}

export function clearPageNav() {
  setPageNav({});
}
