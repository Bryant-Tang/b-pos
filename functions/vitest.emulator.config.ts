import { defineConfig } from 'vitest/config';

// 由 npm run test:emulator 透過 firebase emulators:exec 叫起來，
// 執行時 FIRESTORE_EMULATOR_HOST 已經被設好。
// 兩類測試共用同一個 emulator：Security Rules（用客戶端 SDK 驗權限）
// 與觸發器的整合測試（用 admin SDK，本來就繞過規則）。
export default defineConfig({
  test: {
    include: ['test/rules/**/*.test.ts', 'test/integration/**/*.test.ts'],
    // emulator 冷啟動與規則編譯比純函式慢得多，放寬逾時。
    testTimeout: 20_000,
    hookTimeout: 60_000,
    // 所有測試共用同一個 emulator 實例，平行跑會互相清掉資料。
    fileParallelism: false,
  },
});
