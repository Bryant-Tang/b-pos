import { Timestamp } from 'firebase-admin/firestore';
import { z } from 'zod';

/**
 * 店員端下單意圖的 schema（見 docs/decisions/0001-offline-write-path.md）。
 *
 * 全部 `.strict()`：客戶端偷塞 price、subtotal、total 這類欄位一律讓驗證失敗，
 * 而不是忽略。忽略的話，偷塞的那一方永遠不知道自己被擋，而且「要忽略哪些欄位」
 * 這份清單本身就是會過期的攻擊面（CLAUDE.md 第二節第一條）。
 *
 * Rules 已經擋過一層頂層欄位白名單，但 Rules 沒有迴圈，檢查不了 lines 陣列裡
 * 每一筆的形狀，那一層是這裡補的。
 */

const IntentLineOption = z
  .object({
    groupId: z.string().min(1),
    optionId: z.string().min(1),
  })
  .strict();

const IntentLine = z
  .object({
    itemId: z.string().min(1),
    qty: z.number().int().positive().max(999),
    options: z.array(IntentLineOption).max(20),
  })
  .strict();

const timestamp = z.custom<Timestamp>((v) => v instanceof Timestamp, {
  message: 'clientCreatedAt 必須是 Firestore Timestamp',
});

export const OrderIntentSchema = z
  .object({
    intentId: z.string().min(1),
    orderId: z.string().min(1),
    orderType: z.enum(['dine_in', 'takeout', 'waitlist']),
    tableId: z.string().min(1).nullable(),
    lines: z.array(IntentLine).min(1).max(100),
    createdBy: z.string().min(1),
    clientCreatedAt: timestamp,
  })
  .strict()
  .superRefine((intent, ctx) => {
    // 內用一定要有桌號；外帶沒有桌位，候位則是還沒綁桌（SPEC 第十五節〈三種訂單型態〉）。
    if (intent.orderType === 'dine_in' && intent.tableId === null) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['tableId'],
        message: '內用單必須帶桌號',
      });
    }
    if (intent.orderType !== 'dine_in' && intent.tableId !== null) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['tableId'],
        message: '外帶與候位單不可帶桌號，綁桌要走型態轉換',
      });
    }
  });

export type OrderIntent = z.infer<typeof OrderIntentSchema>;
