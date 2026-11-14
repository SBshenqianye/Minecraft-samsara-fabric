import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import { fileURLToPath, URL } from 'node:url';

const outDir = fileURLToPath(new URL('../mod/src/main/resources/web/vue', import.meta.url));

export default defineConfig({
  base: '/vue/',
  plugins: [
    vue(),
    Components({ resolvers: [ElementPlusResolver()] }),
  ],
  server: {
    fs: {
      allow: ['..'],
    },
  },
  build: {
    outDir,
    emptyOutDir: true,
    sourcemap: false,
    target: 'es2020',
    rollupOptions: {
      output: {
        // 第三方依赖单独成块，业务页面继续按路由懒加载；升级/改页面时缓存互不影响。
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined;
          if (id.includes('element-plus') || id.includes('@element-plus')) {
            return 'element-plus';
          }
          if (id.includes('/vue') || id.includes('@vue')) return 'vue';
          return 'vendor';
        },
      },
    },
  },
});
