import { defineConfig } from 'vitest/config';

// 只測不碰瀏覽器 API 的純邏輯（購物車、菜單整理）。畫面本身靠 build 把關，
// 與 functions 那邊同一個做法：測會賠錢的地方，不為了覆蓋率去測 render。
export default defineConfig({
  test: { include: ['test/**/*.test.ts'], environment: 'node' },
});
