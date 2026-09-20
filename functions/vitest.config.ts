import { defineConfig } from 'vitest/config';

// 預設的 npm test：只跑不需要外部服務的純函式測試。
// 需要 Firestore emulator 的走 vitest.emulator.config.ts。
export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    exclude: ['test/rules/**', 'test/integration/**'],
  },
});
