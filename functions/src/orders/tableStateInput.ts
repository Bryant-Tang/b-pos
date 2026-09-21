import { z } from 'zod';

/**
 * 進場查桌況的請求（顧客掃到 QR code、還沒點任何東西的那一刻）。
 *
 * 與 `CreateOrderInput` 的前兩個欄位刻意一模一樣，包含 token 的格式檢查：
 * 讀得到桌況卻送不出單（或反過來）是最難查的那種 bug。
 *
 * `.strict()` 的理由同 createOrderInput：偷塞的欄位要讓驗證失敗，不是被忽略。
 */
export const TableStateInput = z
  .object({
    storeId: z.string().min(1),
    tableToken: z.string().regex(/^[0-9a-f]{32}$/, 'tableToken 格式不正確'),
  })
  .strict();

export type TableStateInput = z.infer<typeof TableStateInput>;
