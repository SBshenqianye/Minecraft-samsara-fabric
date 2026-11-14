import js from '@eslint/js';
import pluginVue from 'eslint-plugin-vue';
import globals from 'globals';

export default [
  {
    ignores: [
      'node_modules/**',
      '../mod/src/main/resources/web/vue/**',
      // 旧命令式内核：按原样迁入并随包构建，不纳入新代码 lint 规则。
      'src/legacy-core/**',
      'dist/**',
    ],
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['src/**/*.{js,vue}', '*.js'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      // 旧代码里大量“清理函数只判空”的 catch，保留空块但要求显式 catch 参数。
      'no-empty': ['warn', { allowEmptyCatch: true }],
      'no-unused-vars': ['warn', {
        argsIgnorePattern: '^_',
        caughtErrors: 'none',
        varsIgnorePattern: '^_',
      }],
      'vue/multi-word-component-names': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/html-self-closing': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/attributes-order': 'off',
      'vue/no-v-html': 'error',
    },
  },
];
