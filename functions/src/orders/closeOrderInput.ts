import { z } from 'zod';

/**
 * 結帳請求的 schema（SPEC 第五節 `closeOrder`）。
 *
 * **沒有 storeId 欄位，而且不可以加**，理由與其他店員端 schema 相同：
 * 店在哪一間由 token 的 claims 決定（staffAuth.ts）。
 *
 * **也沒有任何金額欄位。** 總額一律由伺服器從單上的品項算好鎖住（CLAUDE.md 第二節第一條）。
 * 唯一的例外是 `payment.received`，那不是價格而是「客人拿了多少現金出來」——
 * 只有櫃檯知道，伺服器算不出來，找零則是伺服器用它減掉總額算的。
 */
export const CloseOrderInput = z
  .object({
    /** 要結帳的那張單。 */
    orderId: z.string().min(1),
    payment: z
      .object({
        method: z.enum(['cash', 'mobile', 'card']),
        /**
         * 客人拿出來的現金，整數。刷卡與行動支付不會有這個欄位——那兩種收的就是總額，
         * 帶進來只會讓「找零」這件事出現在不該出現的地方。
         */
        received: z.number().int().min(0).optional(),
      })
      .strict(),
    /**
     * 冪等鍵，由平板產生。與其他店員端 callable 同一個模式（CLAUDE.md 第二節第三條）。
     *
     * 結帳最需要它：成功之後這張單就從 `orders` 搬進 `orders_archive` 了，重送時
     * 原本的位置已經沒有東西。沒有這個鍵的話，重送看到的是「找不到這張單」，
     * 店員會以為沒結成功而再結一次。
     */
    requestId: z.string().uuid(),
  })
  .strict()
  .refine((input) => input.payment.method === 'cash' || input.payment.received === undefined, {
    message: '只有付現才需要填收到的金額',
    path: ['payment', 'received'],
  });

export type CloseOrderInput = z.infer<typeof CloseOrderInput>;
