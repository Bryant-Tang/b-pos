import { defineConfig } from 'vitest/config';

// 預設的 npm test：只跑不需要外部服務的純函式測試。
// Rules 測試要先起 Firestore emulator，走 vitest.rules.config.ts。
export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    exclude: ['test/rules/**'],
  },
});
