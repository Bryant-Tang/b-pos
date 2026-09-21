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
