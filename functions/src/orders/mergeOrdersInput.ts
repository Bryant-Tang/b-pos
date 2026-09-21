import { z } from 'zod';

/**
 * 併桌／併單請求的 schema（SPEC 第十三節〈分單與併單〉）。
 *
 * **沒有 storeId 欄位，而且不可以加**，理由與 voidOrderLineInput、moveOrderTableInput
 * 完全相同：店在哪一間由 token 的 claims 決定（staffAuth.ts）。
 */
export const MergeOrdersInput = z
  .object({
    /**
     * 併完之後留下的那一張單。
     *
     * SPEC 寫的是 `mergeOrders(orderIds[])`，但一個陣列沒有講清楚哪一張是留下來的。
     * 那件事不能靠約定（「第一個就是目標」）：搞錯的結果是客人的帳掛到別桌去，
     * 而且兩張單的 `pickupCode`、`businessDate`、折扣都不一樣，挑錯就得再併一次。
     */
    targetOrderId: z.string().min(1),
    /**
     * 要併進去的其他單。併完它們會變成 `merged`，桌位轉給目標單。
     *
     * 上限 9 張（連同目標單共 10 張）：一次 transaction 要把所有單與它們的 session
     * 都讀進來，張數沒有上限的話，一個打錯的請求可以讓一筆 transaction 掃過整個
     * `orders` collection。實際營業併的是兩三桌，9 已經遠超過。
     */
    sourceOrderIds: z.array(z.string().min(1)).min(1).max(9),
    /**
     * 冪等鍵，由平板產生。與 voidOrderLine、moveOrderTable 同一個模式
     * （CLAUDE.md 第二節第三條）。
     *
     * 併單特別需要它：第一次成功但回應掉了，重送時來源單已經是 `merged`，
     * 伺服器會回「這張單不能併」。店員看到失敗訊息，很可能改去併別張——
     * 而第一次併進去的品項已經在目標單上了。
     */
    requestId: z.string().uuid(),
  })
  .strict()
  .refine((input) => !input.sourceOrderIds.includes(input.targetOrderId), {
    message: '目標單不能同時是來源單',
    path: ['sourceOrderIds'],
  })
  .refine((input) => new Set(input.sourceOrderIds).size === input.sourceOrderIds.length, {
    // 同一張單列兩次會把它的品項算兩遍，客人被多收一份錢。
    message: '來源單不可以重複',
    path: ['sourceOrderIds'],
  });

export type MergeOrdersInput = z.infer<typeof MergeOrdersInput>;
