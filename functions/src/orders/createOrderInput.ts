import { z } from 'zod';

/**
 * 顧客端下單請求的 schema（SPEC 第二節〈因為沒有資料庫 schema，驗證必須自己做〉）。
 *
 * `.strict()` 是這份檔案的重點：客戶端偷塞 `price`、`subtotal`、`total` 一律讓驗證失敗，
 * 而不是忽略。理由與 intentSchema.ts 相同——忽略的話偷塞的那一方永遠不知道自己被擋，
 * 而且「要忽略哪些欄位」這份清單本身就是會過期的攻擊面（CLAUDE.md 第二節第一條）。
 *
 * 顧客這一邊的上限比店員端嚴：店員有店裡的情境可以判斷，顧客沒有，
 * 而且顧客端是唯一對全世界開放的入口。
 */

const ItemOption = z
  .object({
    groupId: z.string().min(1),
    optionId: z.string().min(1),
  })
  .strict();

const Item = z
  .object({
    itemId: z.string().min(1),
    // 一次點 20 份同一品項已經很極端了，再多八成是誤觸或腳本。
    qty: z.number().int().min(1).max(20),
    options: z.array(ItemOption).max(10).default([]),
  })
  .strict();

export const CreateOrderInput = z
  .object({
    storeId: z.string().min(1),
    /**
     * 這一次送出的識別碼，由客戶端產生（`crypto.randomUUID()`）。
     *
     * 冪等鍵。網路慢的時候客人會連按兩下送出，前端收不到回應時也會重試，
     * 而這兩種情況送出的是「同一張單」不是「再點一份」。沒有這個欄位的話，
     * 第二次呼叫會走加點那條路，把同一批品項再加一次，客人點一份牛肉麵帳上變兩份，
     * 店員還看不出來那是重複還是真的多點了一份。
     *
     * 與店員端 order_intents 的 `intentId` 是同一個模式（CLAUDE.md 第二節第三條：
     * 重試必須冪等）。伺服器把用過的 requestId 記在訂單的 appliedRequestIds 裡，
     * 在 transaction 內比對。
     *
     * 必填而不是選填：選填等於讓忘了帶的客戶端安靜地失去保護。
     */
    requestId: z.string().uuid(),
    /**
     * 印在桌上的 QR token，由 createTable 用 `randomBytes(16)` 產生，固定 32 碼小寫十六進位。
     *
     * 用正規表示式而不是只檢查長度，是為了讓格式不對的輸入在進 Firestore 查詢之前就被擋掉
     * （SPEC 第五節〈createTable〉：不可預測的 token 是顧客端唯一的「我在這張桌旁」證明）。
     */
    tableToken: z.string().regex(/^[0-9a-f]{32}$/, 'tableToken 格式不正確'),
    items: z.array(Item).min(1).max(50),
  })
  .strict();

export type CreateOrderInput = z.infer<typeof CreateOrderInput>;
