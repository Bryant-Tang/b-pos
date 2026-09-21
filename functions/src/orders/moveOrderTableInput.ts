import { z } from 'zod';

/**
 * 店員轉桌請求的 schema（SPEC 第九節〈訂單明細〉的「轉桌」）。
 *
 * **沒有 storeId 欄位，而且不可以加**，理由與 voidOrderLineInput 完全相同：
 * 店在哪一間由 token 的 claims 決定（staffAuth.ts），輸入裡帶得動 storeId
 * 等於讓任何店員對任何一間店動手。
 */
export const MoveOrderTableInput = z
  .object({
    orderId: z.string().min(1),
    /**
     * 要從哪一桌搬走。
     *
     * 看起來多餘——大部分的單就只有一桌，平板明明可以不帶。但併桌之後的單
     * `tableIds` 會有兩桌以上（SPEC 第十三節〈分單與併單〉），那時候「轉桌」
     * 沒有指名就沒有答案：搬哪一桌？把整張單搬到一桌又會把併桌悄悄拆掉。
     * 要求平板指名，是讓這個歧義在呼叫端就被解決，而不是由伺服器猜。
     */
    fromTableId: z.string().min(1),
    /** 要搬到哪一桌。必須是這間店裡沒有封存、而且現在沒人在用的桌。 */
    toTableId: z.string().min(1),
    /**
     * 冪等鍵，由平板產生。與 voidOrderLine 的 requestId 同一個模式
     * （CLAUDE.md 第二節第三條）。
     *
     * 轉桌特別需要它：第一次呼叫成功但回應在路上掉了，平板重送時，桌位已經不在
     * `fromTableId` 上，伺服器會回「這張單不在那一桌」。店員看到的是一個失敗訊息，
     * 但實際上已經轉成功了——接著他很可能去把單再轉一次，而這次是從對的桌轉到別處。
     */
    requestId: z.string().uuid(),
  })
  .strict();

export type MoveOrderTableInput = z.infer<typeof MoveOrderTableInput>;
