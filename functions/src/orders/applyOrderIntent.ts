import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import type { DocumentReference, Firestore, Transaction } from 'firebase-admin/firestore';
import { OrderIntentSchema, type OrderIntent } from './intentSchema.js';
import { readStoredLines, tenantRefs, toStoredLine } from './orderDocs.js';
import { businessDateOf, formatPickupCode } from './businessDate.js';
import { readBusinessSettings, readPricingSettings } from './settings.js';
import {
  PricingError,
  calcOrderLines,
  calcOrderTotal,
  type MenuSnapshot,
  type OrderLine,
} from './pricing.js';

/**
 * 把店員端的下單意圖套用成真正的訂單。
 *
 * 這是 docs/decisions/0001-offline-write-path.md 說的那支觸發器：店員平板只寫
 * order_intents（只有品項 ID 與數量），價格一律由這裡從 published/menu 查出來算。
 *
 * 兩件事決定了它的形狀：
 *
 * 1. **觸發器是 at-least-once**，同一份意圖可能被送進來兩次以上。冪等鍵是 intentId，
 *    記在訂單的 appliedIntentIds 裡，整段在 transaction 內檢查。
 * 2. **失敗不能用丟例外解決。** 丟出去之後店員只會看到單沒出現，不知道為什麼。
 *    所有「重試幾次都不會成功」的失敗都寫回意圖文件的 rejectReason，讓平板顯示原因。
 */

export type RejectReason =
  | 'invalid_intent'
  | 'menu_not_published'
  | 'unknown_table'
  | 'order_closed'
  | 'order_type_mismatch'
  | 'table_mismatch'
  | 'pricing_error';

export type ApplyOutcome =
  | { status: 'created'; orderId: string; pickupCode: string | null }
  | { status: 'appended'; orderId: string }
  | { status: 'already_applied'; orderId: string }
  | { status: 'rejected'; reason: RejectReason; detail: string };

/** 可以附加新品項的狀態：已結帳與已作廢的單不能再動（SPEC 第十三節〈結帳是硬分界線〉）。 */
const APPENDABLE_STATUSES = new Set(['open', 'pending_confirm']);

/** 外帶與候位共用一套當日發號，4 碼、每日歸零（SPEC 第十四節〈訂單號規則〉）。 */
async function nextPickupCode(
  tx: Transaction,
  counterRef: DocumentReference,
): Promise<string> {
  const snap = await tx.get(counterRef);
  const current = snap.exists ? (snap.data()?.['nextPickupCode'] as unknown) : undefined;
  const n = typeof current === 'number' && Number.isInteger(current) && current >= 1 ? current : 1;
  tx.set(counterRef, { nextPickupCode: n + 1 }, { merge: true });
  return formatPickupCode(n);
}

export async function applyOrderIntent(
  db: Firestore,
  storeId: string,
  intentId: string,
  raw: unknown,
  now: Date,
): Promise<ApplyOutcome> {
  const refs = tenantRefs(db, storeId);

  const reject = async (reason: RejectReason, detail: string): Promise<ApplyOutcome> => {
    await refs.intent(intentId).set(
      { rejectedAt: Timestamp.fromDate(now), rejectReason: reason, rejectDetail: detail },
      { merge: true },
    );
    return { status: 'rejected', reason, detail };
  };

  const parsed = OrderIntentSchema.safeParse(raw);
  if (!parsed.success) {
    return reject('invalid_intent', parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join('; '));
  }
  const intent: OrderIntent = parsed.data;
  if (intent.intentId !== intentId) {
    return reject('invalid_intent', `intentId 與文件 id 不一致：${intent.intentId} / ${intentId}`);
  }

  // 菜單與設定刻意讀在 transaction 外面：它們是老闆手動按發佈才會變的東西，
  // 拉進 transaction 只會讓尖峰時段的每一張單都跟著 published/menu 競爭重試。
  // 寫進訂單的 lines 快照與 menuVersion 都來自同一次讀取，所以訂單本身自洽；
  // 極端情況下剛好撞上 publishMenu，結果是這張單用舊版菜單的價，那正是
  // 「訂單必須存價格快照」要的行為（SPEC 第三節第二條）。
  const [menuSnap, pricingSnap, businessSnap] = await Promise.all([
    refs.menu.get(),
    refs.pricingSettings.get(),
    refs.businessSettings.get(),
  ]);
  if (!menuSnap.exists) {
    return reject('menu_not_published', '還沒有發佈過菜單，請先在後台按發佈');
  }
  const menuData = menuSnap.data() ?? {};
  // pricing.ts 刻意不認得 Firestore，MenuSnapshot 只有算價需要的欄位，
  // published/menu 的 version 另外讀。
  const menu = menuData as unknown as MenuSnapshot;
  const menuVersion = typeof menuData['version'] === 'number' ? menuData['version'] : null;
  const pricing = readPricingSettings(pricingSnap.data());
  const business = readBusinessSettings(businessSnap.data());

  let tableLabel: string | null = null;
  if (intent.tableId !== null) {
    const tableSnap = await refs.table(intent.tableId).get();
    if (!tableSnap.exists) {
      return reject('unknown_table', `找不到桌位 ${intent.tableId}`);
    }
    tableLabel = String(tableSnap.data()?.['label'] ?? '');
  }

  let newLines: OrderLine[];
  try {
    newLines = calcOrderLines(menu, intent.lines, intent.orderType);
  } catch (err) {
    if (err instanceof PricingError) return reject('pricing_error', `${err.code}: ${err.message}`);
    throw err;
  }

  const orderRef = refs.order(intent.orderId);
  const businessDate = businessDateOf(now, business);
  const nowTs = Timestamp.fromDate(now);

  const outcome = await db.runTransaction(async (tx): Promise<ApplyOutcome> => {
    const orderSnap = await tx.get(orderRef);

    if (!orderSnap.exists) {
      const pickupCode =
        intent.orderType === 'dine_in'
          ? null
          : await nextPickupCode(tx, refs.counter(businessDate));
      const totals = calcOrderTotal(newLines, intent.orderType, pricing);

      tx.set(orderRef, {
        orderType: intent.orderType,
        pickupCode,
        tableIds: intent.tableId === null ? [] : [intent.tableId],
        tableLabels: tableLabel === null ? [] : [tableLabel],
        sessionIds: [],
        status: 'open',
        source: 'staff',
        lines: newLines.map(toStoredLine),
        subtotal: totals.subtotal,
        serviceCharge: totals.serviceCharge,
        discount: totals.discount,
        total: totals.total,
        taxSummary: totals.taxSummary,
        // businessDate 由 closeOrder 在結帳當下寫入，營收歸給結帳那一天（SPEC 第五節）。
        businessDate: null,
        // 這張單是用哪一版菜單算的，日後對帳查得到。
        menuVersion,
        appliedIntentIds: [intent.intentId],
        createdAt: nowTs,
        updatedAt: nowTs,
        closedAt: null,
        createdBy: intent.createdBy,
      });
      tx.set(refs.intent(intentId), { appliedAt: nowTs, orderId: intent.orderId }, { merge: true });
      return { status: 'created', orderId: intent.orderId, pickupCode };
    }

    const order = orderSnap.data() ?? {};
    const applied = (order['appliedIntentIds'] as string[] | undefined) ?? [];
    if (applied.includes(intent.intentId)) {
      return { status: 'already_applied', orderId: intent.orderId };
    }

    const status = String(order['status'] ?? '');
    if (!APPENDABLE_STATUSES.has(status)) {
      return { status: 'rejected', reason: 'order_closed', detail: `訂單狀態是 ${status}，不能再加點` };
    }
    if (order['orderType'] !== intent.orderType) {
      return {
        status: 'rejected',
        reason: 'order_type_mismatch',
        detail: `訂單是 ${String(order['orderType'])}，意圖是 ${intent.orderType}，型態轉換要走轉換流程`,
      };
    }
    if (intent.tableId !== null && !((order['tableIds'] as string[] | undefined) ?? []).includes(intent.tableId)) {
      return {
        status: 'rejected',
        reason: 'table_mismatch',
        detail: `桌位 ${intent.tableId} 不屬於這張單，併桌要走 mergeOrders`,
      };
    }

    const existing = readStoredLines(order);
    const merged = [...existing, ...newLines];
    const discount = typeof order['discount'] === 'number' ? (order['discount'] as number) : 0;
    const totals = calcOrderTotal(merged, intent.orderType, pricing, discount);

    tx.update(orderRef, {
      lines: merged.map(toStoredLine),
      subtotal: totals.subtotal,
      serviceCharge: totals.serviceCharge,
      total: totals.total,
      taxSummary: totals.taxSummary,
      appliedIntentIds: FieldValue.arrayUnion(intent.intentId),
      updatedAt: nowTs,
    });
    tx.set(refs.intent(intentId), { appliedAt: nowTs, orderId: intent.orderId }, { merge: true });
    return { status: 'appended', orderId: intent.orderId };
  });

  // transaction 裡不能寫 rejectReason（一寫就沒辦法乾淨地放棄整筆），所以拒絕的
  // 理由留到這裡補記。
  if (outcome.status === 'rejected') {
    return reject(outcome.reason, outcome.detail);
  }
  return outcome;
}
