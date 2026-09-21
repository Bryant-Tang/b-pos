import { z } from 'zod';

/**
 * 店員確認顧客自助單的請求 schema（SPEC 第五節 `confirmGuestOrder`）。
 *
 * **沒有 storeId 欄位，而且不可以加**，理由與 voidOrderLineInput、moveOrderTableInput、
 * mergeOrdersInput 完全相同：店在哪一間由 token 的 claims 決定（staffAuth.ts）。
 */
export const ConfirmGuestOrderInput = z
  .object({
    /** 要確認的那張單。待確認列表上的每一列就是一張。 */
    orderId: z.string().min(1),
    /**
     * 冪等鍵，由平板產生。與其他店員端 callable 同一個模式（CLAUDE.md 第二節第三條）。
     *
     * 確認特別需要它：平板一確認就會印廚房單，而「已經成功但回應掉了」時店員看到的是
     * 失敗，很可能再按一次。沒有這個鍵的話，第二次會被當成另一次確認，廚房收到兩張。
     */
    requestId: z.string().uuid(),
  })
  .strict();

export type ConfirmGuestOrderInput = z.infer<typeof ConfirmGuestOrderInput>;
