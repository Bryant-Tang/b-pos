/**
 * 訂單在 Firestore 這一層的共用東西：文件路徑，以及訂單行的存讀轉換。
 *
 * 算價那一層（pricing.ts）刻意不認得 Firestore，用的是 `Date`；存進文件的則必須是
 * `Timestamp`，否則查詢與排序都會把它當成一般 map。兩種形狀的轉換只該有一份，
 * 不然遲早會有一支 function 寫進去的 `voidedAt` 是字串。
 */

import { Timestamp } from 'firebase-admin/firestore';
import type { Firestore } from 'firebase-admin/firestore';
import type { OrderLine } from './pricing.js';

/** 與 `OrderLine` 同形，只差兩個時間欄位是 Firestore 的 `Timestamp`。 */
export interface StoredLine extends Omit<OrderLine, 'printedAt' | 'voidedAt'> {
  printedAt: Timestamp | null;
  voidedAt: Timestamp | null;
}

function toTimestamp(value: Date | null): Timestamp | null {
  return value === null ? null : Timestamp.fromDate(value);
}

/**
 * 讀回來的東西不保證是 `Timestamp`。
 *
 * 型別上 `StoredLine` 說它是，實際上這些值是從 Firestore 文件硬轉過來的：舊的訂單
 * 根本沒有 `printedAt` 這個欄位（在它加進來以前建的單），拿到的是 `undefined`。
 * 不判斷就直接 `.toDate()`，結果是整張單讀不出來——而那是一張營業中的單。
 */
function toDate(value: unknown): Date | null {
  return value instanceof Timestamp ? value.toDate() : null;
}

export function toStoredLine(line: OrderLine): StoredLine {
  return {
    ...line,
    printedAt: toTimestamp(line.printedAt),
    voidedAt: toTimestamp(line.voidedAt),
  };
}

export function toPricingLine(line: StoredLine): OrderLine {
  return {
    ...line,
    printedAt: toDate(line.printedAt),
    voidedAt: toDate(line.voidedAt),
    voidReason: typeof line.voidReason === 'string' ? line.voidReason : null,
  };
}

/** 讀出訂單文件裡的 lines；沒有或形狀不對時回空陣列。 */
export function readStoredLines(order: Record<string, unknown>): OrderLine[] {
  const lines = order['lines'];
  return Array.isArray(lines) ? (lines as StoredLine[]).map(toPricingLine) : [];
}

/**
 * 讀訂單文件上的金額欄位；不是數字就當 0。
 *
 * 壞掉的一個欄位不該變成 `NaN` 一路傳到客人或店員的畫面上——`NaN` 在畫面上是
 * 「NT$ NaN」，而且它跟任何數字比大小都是 false，後面的判斷會安靜地走錯邊。
 */
export function readAmount(order: Record<string, unknown>, key: string): number {
  const value = order[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

/**
 * 一間店底下所有會用到的文件路徑。
 *
 * 集中在這裡是因為多租戶前綴（SPEC 第三節）錯一個字不會有任何錯誤訊息——
 * 只會安靜地讀到空文件，或把資料寫到一個沒有規則保護的路徑上。
 */
export function tenantRefs(db: Firestore, storeId: string) {
  const root = `tenants/${storeId}`;
  return {
    menu: db.doc(`${root}/published/menu`),
    pricingSettings: db.doc(`${root}/settings/pricing`),
    businessSettings: db.doc(`${root}/settings/business`),
    tables: db.collection(`${root}/tables`),
    table: (id: string) => db.doc(`${root}/tables/${id}`),
    orders: db.collection(`${root}/orders`),
    order: (id: string) => db.doc(`${root}/orders/${id}`),
    // 已結帳的單搬到這裡，`orders` 才會永遠只有數十筆（SPEC 第十一節第 2 點）。
    archivedOrder: (id: string) => db.doc(`${root}/orders_archive/${id}`),
    // 收據的文件 id 就是 sessionId，那是客人手機裡那把鑰匙（SPEC 第十三節）。
    receipt: (sessionId: string) => db.doc(`${root}/receipts/${sessionId}`),
    intent: (id: string) => db.doc(`${root}/order_intents/${id}`),
    session: (id: string) => db.doc(`${root}/sessions/${id}`),
    counter: (businessDate: string) => db.doc(`${root}/counters/${businessDate}`),
    rateLimit: (uid: string) => db.doc(`${root}/rate_limits/${uid}`),
  };
}
