/**
 * 顧客掃到 QR code、還沒點東西之前，先問這張桌現在什麼狀況。
 *
 * 補的是一個實際測出來的洞：在這支之前，網頁在送出第一筆之前對這張桌一無所知，
 * 因為桌號與訂單都只從 `createOrder` 的回傳值來。造成兩件事——
 *
 * 1. **看不到桌號。** 客人掃錯桌要等到點完才發現。
 * 2. **送出後才冒出別人的品項。** 同桌共用一張單是對的（SPEC 第十三節），
 *    但菜單頁看起來像全新的一單，送出後突然多出前面的東西，客人會以為點錯了。
 *
 * 兩件事的根都是「寫進去才知道」，所以補一支只讀不寫的。
 *
 * **只回總數與總額，不回品項明細。** 掃到桌上那張 QR code 的不保證是同桌的人，
 * 而要移除那個驚嚇只需要「這桌已經點了幾份、多少錢」。客人自己送出一次之後，
 * `createOrder` 的回傳值才給完整明細——那時他本來就是這張單的當事人。
 *
 * 權限上沒有開出新的口子：能呼叫這支就代表手上有 token，而有 token 本來就能呼叫
 * `createOrder`、從回傳值看到整張單。硬要說是收斂了——在這支之前，想看這桌點了什麼
 * 得先加點一份東西進去。
 */

import type { Firestore } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { readStoredLines, tenantRefs } from './orderDocs.js';
import { findTableByToken } from './tables.js';
import { consumeRateLimit, type RateLimitPolicy } from './rateLimit.js';
import type { TableStateInput } from './tableStateInput.js';

/**
 * 讀取用的限流，與送出分開計數。
 *
 * 共用一個計數器的話，重新整理幾次頁面就會把送出的額度吃光，客人變成點得了餐卻送不出去。
 * 比送出寬鬆是因為這支每次進場都會打一次，而且它不寫任何東西。
 */
export const TABLE_STATE_RATE_LIMIT: RateLimitPolicy = { max: 30, windowSec: 60 };

/**
 * 還算「這一攤還沒結束」的狀態，與 createGuestOrder 的 APPENDABLE_STATUSES 同一組。
 * 已結帳或已作廢的單不該在菜單頁上顯示成「這桌已經點了…」——那一攤結束了，
 * 客人這次掃進來是新的一攤。
 */
const OPEN_STATUSES = new Set(['open', 'pending_confirm']);

export interface GuestTableStateView {
  tableLabel: string;
  /** 這張桌目前未結帳的單；沒有就是 null，客人這次是這一攤的第一個。 */
  openOrder: {
    /** 已點的份數總和，作廢的行不算。 */
    itemCount: number;
    total: number;
    status: string;
  } | null;
}

export async function getTableState(
  db: Firestore,
  input: TableStateInput,
  uid: string,
  now: Date,
): Promise<GuestTableStateView> {
  const refs = tenantRefs(db, input.storeId);

  // 計數器的 key 刻意與送出那支分開（見 TABLE_STATE_RATE_LIMIT）。
  const limit = await consumeRateLimit(
    db,
    refs.rateLimit(`${uid}_read`),
    TABLE_STATE_RATE_LIMIT,
    now,
  );
  if (!limit.allowed) {
    throw new HttpsError('resource-exhausted', `重新整理得太頻繁了，請等 ${limit.retryAfterSec} 秒後再試`);
  }

  const table = await findTableByToken(db, input.storeId, input.tableToken);
  if (table === null) {
    // 與 createOrder 同一句話：客人分不出「token 打錯」與「這桌被停用」，
    // 兩種的處置也都是找服務人員。
    throw new HttpsError('permission-denied', '這張 QR code 已經失效，請洽服務人員');
  }

  const none: GuestTableStateView = { tableLabel: table.label, openOrder: null };

  // 不用 transaction：這是一次進場查詢，讀到的是哪一個瞬間都可以——真正決定併不併單的
  // 是 createOrder 裡那段有鎖的 transaction。這裡讀到舊的，最差就是畫面上少講一句提示。
  // 指標由 findTableByToken 的查詢一併帶回來，不再為了一個欄位重讀一次桌位文件。
  if (table.activeSessionId === null) return none;

  const sessionSnap = await refs.session(table.activeSessionId).get();
  const session = sessionSnap.data();
  // 指標指到已結束的 session 時當成沒有——與 createGuestOrder 同一個防呆：
  // closeOrder 應該清掉指標，萬一漏了，結果要是「開新的一攤」而不是卡住。
  if (!sessionSnap.exists || session?.['status'] !== 'active') return none;

  const orderId = String(session['orderId'] ?? '');
  if (orderId.length === 0) return none;

  const orderSnap = await refs.order(orderId).get();
  if (!orderSnap.exists) return none;
  const order = orderSnap.data() ?? {};

  const status = String(order['status'] ?? '');
  if (!OPEN_STATUSES.has(status)) return none;

  const total = order['total'];
  return {
    tableLabel: table.label,
    openOrder: {
      itemCount: readStoredLines(order)
        .filter((line) => line.voidedAt === null)
        .reduce((sum, line) => sum + line.qty, 0),
      total: typeof total === 'number' && Number.isFinite(total) ? total : 0,
      status,
    },
  };
}
