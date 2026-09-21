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

/** 與 `OrderLine` 同形，只差 `voidedAt` 是 Firestore 的 `Timestamp`。 */
export interface StoredLine extends Omit<OrderLine, 'voidedAt'> {
  voidedAt: Timestamp | null;
}

export function toStoredLine(line: OrderLine): StoredLine {
  return { ...line, voidedAt: line.voidedAt === null ? null : Timestamp.fromDate(line.voidedAt) };
}

export function toPricingLine(line: StoredLine): OrderLine {
  return { ...line, voidedAt: line.voidedAt === null ? null : line.voidedAt.toDate() };
}

/** 讀出訂單文件裡的 lines；沒有或形狀不對時回空陣列。 */
export function readStoredLines(order: Record<string, unknown>): OrderLine[] {
  const lines = order['lines'];
  return Array.isArray(lines) ? (lines as StoredLine[]).map(toPricingLine) : [];
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
    intent: (id: string) => db.doc(`${root}/order_intents/${id}`),
    session: (id: string) => db.doc(`${root}/sessions/${id}`),
    counter: (businessDate: string) => db.doc(`${root}/counters/${businessDate}`),
    rateLimit: (uid: string) => db.doc(`${root}/rate_limits/${uid}`),
  };
}
