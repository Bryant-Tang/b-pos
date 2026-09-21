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
    /**
     * 客人手上那一攤的 sessionId，沒有就不帶（第一次掃進來的人就是沒有）。
     *
     * 這是**客人自己拿來問「我那一攤還是這桌現在這一攤嗎」**用的，伺服器只回是或不是，
     * 不會把現行的 sessionId 送出去。sessionId 是能力憑證（見 createGuestOrder），
     * 送出去等於讓任何掃得到這張 QR code 的人都能冒充上一組客人。
     *
     * 格式檢查跟產生端同一套：猜中一個 32 碼十六進位要 2^128 次，而且讀取有限流，
     * 所以這個比對不構成可用的探測管道。
     */
    sessionId: z.string().regex(/^[0-9a-f]{32}$/, 'sessionId 格式不正確').optional(),
  })
  .strict();

export type TableStateInput = z.infer<typeof TableStateInput>;
