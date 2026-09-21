import { z } from 'zod';

/**
 * 店員退點請求的 schema（SPEC 第五節〈voidOrderLine〉）。
 *
 * **沒有 storeId 欄位，而且不可以加。** 店在哪一間由 token 的 claims 決定
 * （staffAuth.ts），輸入裡帶得動 storeId 等於讓任何店員對任何一間店動手。
 *
 * `.strict()` 的理由與 createOrderInput 相同：偷塞的欄位要讓驗證失敗，不是被忽略。
 * 這一支尤其重要——退點會重算總額，如果哪天有人在輸入裡加了 `total` 而 schema 默默
 * 忽略它，下一個讀這段程式的人不會知道那個欄位有沒有被用到。
 */
export const VoidOrderLineInput = z
  .object({
    orderId: z.string().min(1),
    /** 要退的那一行，由伺服器在 calcOrderLines 時產生的 UUID。 */
    lineId: z.string().min(1),
    /**
     * 冪等鍵，由平板產生。與 createOrder 的 requestId、order_intents 的 intentId 同一個模式
     * （CLAUDE.md 第二節第三條）。
     *
     * 退點看起來天生冪等——已經作廢的再作廢一次不會變什麼。但「刪掉」那一半不是：
     * 沒送廚房的行是真的從陣列裡移除的，重送一次會找不到那一行，於是平板收到
     * 「找不到這一筆」，店員以為沒退成功又退一次。requestId 讓重送回到同一個答案。
     */
    requestId: z.string().uuid(),
    /**
     * 作廢原因，選填。會印在作廢單上給廚房看（SPEC 第八節）。
     *
     * 不設成必填：尖峰時段強迫店員打字，實際結果是每一張都填「客人不要」，
     * 那個欄位就失去意義了，而退點本身還是得做。
     */
    reason: z.string().trim().min(1).max(100).optional(),
  })
  .strict();

export type VoidOrderLineInput = z.infer<typeof VoidOrderLineInput>;
