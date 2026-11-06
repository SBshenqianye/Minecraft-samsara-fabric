import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

const outDir = fileURLToPath(new URL('../mod/src/main/resources/web/vue', import.meta.url));

export default defineConfig({
  base: '/vue/',
  plugins: [vue()],
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
        manualChunks: undefined,
      },
    },
  },
});
