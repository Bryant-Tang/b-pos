/**
 * 店員確認顧客自助單：`pending_confirm` → `open`（SPEC 第五節 `confirmGuestOrder`）。
 *
 * 這是顧客自助點餐這條路上最後一道人為關卡。`createGuestOrder` 刻意讓每一張新的顧客單
 * 都停在 `pending_confirm`（見那支檔案開頭第 3 點），沒有人按過確認就不會進廚房——
 * SPEC 第十二節〈階段 4〉的驗收條件寫的就是「掃 QR 下單，店員確認後才出單」。
 *
 * **這支只改狀態，不印單。** 印表機接在平板上（SPEC 第八節），所以廚房單是平板看到
 * 狀態變成 `open` 之後自己送出去的。伺服器這邊硬要去碰列印，離線時反而會變成
 * 「伺服器以為印了、實際沒印」。
 *
 * 與 voidOrderLine、moveOrderTable、mergeOrders 一樣，刻意只吃 `Firestore` 與已經驗過的
 * 輸入，不碰 onCall 的 request 物件，這樣 emulator 測試可以直接呼叫。
 */

import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import type { Firestore } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import type { StaffCaller } from '../auth/staffAuth.js';
import { tenantRefs } from './orderDocs.js';
import type { ConfirmGuestOrderInput } from './confirmGuestOrderInput.js';

export type ConfirmOutcome =
  /** 真的把這張單放行了 */
  | 'confirmed'
  /** 這張單已經是 open——別台平板先按了，或這是店員手開的單 */
  | 'already_open'
  /** 同一個 requestId 已經處理過，這次什麼都沒改 */
  | 'already_applied';

export interface ConfirmGuestOrderResult {
  orderId: string;
  outcome: ConfirmOutcome;
  /** 這次結束後這張單的狀態。三種 outcome 底下都會是 `open`。 */
  status: string;
}

export async function confirmGuestOrder(
  db: Firestore,
  input: ConfirmGuestOrderInput,
  caller: StaffCaller,
  now: Date,
): Promise<ConfirmGuestOrderResult> {
  const refs = tenantRefs(db, caller.storeId);
  const nowTs = Timestamp.fromDate(now);

  return db.runTransaction(async (tx): Promise<ConfirmGuestOrderResult> => {
    // refs 的 storeId 來自 token，所以別家店的單在這裡就是「找不到」，
    // 不需要、也不應該再拿輸入裡的店號比對一次（staffAuth.ts 的註解）。
    const orderRef = refs.order(input.orderId);
    const snap = await tx.get(orderRef);
    if (!snap.exists) {
      throw new HttpsError('not-found', '找不到這張單，請重新整理待確認列表');
    }

    const order = snap.data() ?? {};
    const status = String(order['status'] ?? '');

    // 冪等要在 transaction 內比對，理由同 voidOrderLine：兩次呼叫同時進來時，
    // 在外面比對會兩邊都讀到「還沒用過」。
    const applied = order['appliedRequestIds'];
    if (Array.isArray(applied) && applied.map((id) => String(id)).includes(input.requestId)) {
      return { orderId: orderRef.id, outcome: 'already_applied', status };
    }

    if (status === 'open') {
      // 兩台平板同時看著待確認列表，另一台先按了。店員要的結果已經成立，
      // 所以回成功而不是丟錯：丟錯只會讓他再按一次，或改去動別張單。
      //
      // 這裡也不覆寫 confirmedAt / confirmedBy——真正放行的是先按的那個人，
      // 而那兩個欄位是之後對「這張單什麼時候進廚房」唯一查得到的紀錄。
      return { orderId: orderRef.id, outcome: 'already_open', status };
    }

    if (status !== 'pending_confirm') {
      throw new HttpsError('failed-precondition', notConfirmableMessage(status));
    }

    tx.update(orderRef, {
      status: 'open',
      confirmedAt: nowTs,
      confirmedBy: caller.uid,
      updatedAt: nowTs,
      appliedRequestIds: FieldValue.arrayUnion(input.requestId),
    });

    return { orderId: orderRef.id, outcome: 'confirmed', status: 'open' };
  });
}

/**
 * 狀態不對時的訊息分得細，是因為店員接下來要做的事不一樣：已結帳要去查那張單，
 * 作廢要問是誰按的，併走要去看目標單。一律回「操作失敗」等於什麼都沒講
 * （SPEC 第十三節〈邊界情況〉對顧客端的要求，店員端同理）。
 */
function notConfirmableMessage(status: string): string {
  if (status === 'closed') return '這張單已經結帳了，不用再確認';
  if (status === 'voided') return '這張單已經作廢了';
  if (status === 'merged') return '這張單已經併進別張單，請到併過去的那張單確認';
  return '這張單現在的狀態不能確認，請重新整理待確認列表';
}
