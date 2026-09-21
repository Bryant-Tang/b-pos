/**
 * 顧客掃桌上的 QR code 自助點餐（SPEC 第五節〈createOrder〉、第十三節）。
 *
 * 三件事決定了它的形狀：
 *
 * 1. **顧客不能直接寫 Firestore**（CLAUDE.md 第二節第二條）。所以顧客端唯一的寫入途徑
 *    就是這支函式，`orders` 對顧客永遠是唯讀。
 * 2. **價格只從 published/menu 來。** 請求裡只有品項 ID 與數量，`.strict()` 讓偷塞的
 *    金額欄位直接驗證失敗（createOrderInput.ts）。
 * 3. **新單一律是 pending_confirm。** 就算限流被繞過、真的被灌進一千張假單，浪費的是
 *    店員滑掉的三秒，而不是一千張紙與一個崩潰的廚房（SPEC 第五節〈為什麼顧客單要
 *    pending_confirm〉）。不要為了流暢體驗把它拿掉。
 *
 * 這一層刻意只吃 `Firestore` 與已經驗過的輸入，不碰 `onCall` 的 request 物件，
 * 這樣 emulator 測試可以直接呼叫它，不必架一個假的 callable context。
 */

import { randomBytes } from 'node:crypto';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import type { Firestore } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import type { CreateOrderInput } from './createOrderInput.js';
import { readAmount, readStoredLines, tenantRefs, toStoredLine } from './orderDocs.js';
import { findTableByToken } from './tables.js';
import { readPricingSettings } from './settings.js';
import { consumeRateLimit, type RateLimitPolicy } from './rateLimit.js';
import {
  PricingError,
  calcOrderLines,
  calcOrderTotal,
  repriceLines,
  type MenuSnapshot,
  type OrderLine,
  type OrderType,
} from './pricing.js';

/**
 * 顧客自助單一律是內用。
 *
 * 外帶與候位沒有桌上的 QR code 可以掃，是店員在平板上建的（SPEC 第十五節），
 * 所以這支函式不接受 orderType 參數——能掃到 token 就代表人在那張桌旁。
 */
const GUEST_ORDER_TYPE: OrderType = 'dine_in';

/** 每個匿名 uid 每分鐘最多送出幾次（SPEC 第五節〈createOrder〉步驟 2）。 */
export const GUEST_RATE_LIMIT: RateLimitPolicy = { max: 5, windowSec: 60 };

/** 可以附加新品項的狀態，與 applyOrderIntent 一致：已結帳與已作廢的單不能再動。 */
const APPENDABLE_STATUSES = new Set(['open', 'pending_confirm']);

/** 回給顧客端的訂單樣貌。只有顧客本來就看得到的東西，沒有 uid、createdBy 之類的內部欄位。 */
export interface GuestOrderView {
  orderId: string;
  sessionId: string;
  tableLabel: string;
  status: string;
  lines: {
    lineId: string;
    name: string;
    qty: number;
    options: { name: string; priceDelta: number }[];
    subtotal: number;
  }[];
  subtotal: number;
  serviceCharge: number;
  discount: number;
  total: number;
}

function view(
  orderId: string,
  sessionId: string,
  tableLabel: string,
  status: string,
  lines: OrderLine[],
  totals: { subtotal: number; serviceCharge: number; discount: number; total: number },
): GuestOrderView {
  return {
    orderId,
    sessionId,
    tableLabel,
    status,
    lines: lines
      .filter((line) => line.voidedAt === null)
      .map((line) => ({
        lineId: line.lineId,
        name: line.name,
        qty: line.qty,
        options: line.options.map((o) => ({ name: o.name, priceDelta: o.priceDelta })),
        subtotal: line.subtotal,
      })),
    subtotal: totals.subtotal,
    serviceCharge: totals.serviceCharge,
    discount: totals.discount,
    total: totals.total,
  };
}

/**
 * 算價失敗要對顧客說什麼。
 *
 * 顧客手上那份菜單可能是幾分鐘前讀的靜態 menu.json，老闆中途下架品項或改了選項，
 * 這裡就會擋下來。訊息要講「怎麼辦」，不是講程式碼裡的錯誤代碼——
 * 客人看到「unknown_item」只會去叫店員（SPEC 第十三節〈邊界情況〉的同一個道理）。
 */
function pricingMessage(err: PricingError): string {
  switch (err.code) {
    case 'unknown_item':
    case 'unknown_option':
      return '菜單剛剛更新了，請重新整理頁面再點一次';
    case 'invalid_options':
      return '餐點的選項有誤，請重新選擇';
    default:
      return '訂單內容有誤，請重新整理頁面再試一次';
  }
}

export async function createGuestOrder(
  db: Firestore,
  input: CreateOrderInput,
  uid: string,
  now: Date,
): Promise<GuestOrderView> {
  const refs = tenantRefs(db, input.storeId);

  // 限流放在最前面。SPEC 第五節的範例把它排在查桌號之後，這裡刻意提前：限流要擋的
  // 正是「一直打這支函式」本身，排在讀取之後等於每一次被擋下的呼叫仍然花掉一次查詢。
  // 代價是 token 打錯也會計次，而實務上 token 是從 QR code 掃來的，不會打錯。
  const limit = await consumeRateLimit(db, refs.rateLimit(uid), GUEST_RATE_LIMIT, now);
  if (!limit.allowed) {
    throw new HttpsError(
      'resource-exhausted',
      `送出得太頻繁了，請等 ${limit.retryAfterSec} 秒後再試`,
    );
  }

  // 驗的是 token 不是 ?table=5。可預測的桌號等於讓任何人在家就能對任意桌下單
  // （SPEC 第五節〈createTable〉）。
  const table = await findTableByToken(db, input.storeId, input.tableToken);
  if (table === null) {
    throw new HttpsError('permission-denied', '這張 QR code 已經失效，請洽服務人員');
  }

  // 菜單與設定讀在 transaction 外面，理由同 applyOrderIntent：它們是老闆手動按發佈
  // 才會變的東西，拉進 transaction 只會讓尖峰時段每一張單都跟著 published/menu 競爭重試。
  const [menuSnap, pricingSnap] = await Promise.all([refs.menu.get(), refs.pricingSettings.get()]);
  if (!menuSnap.exists) {
    throw new HttpsError('failed-precondition', '店家還沒有發佈菜單，請洽服務人員');
  }
  const menuData = menuSnap.data() ?? {};
  const menu = menuData as unknown as MenuSnapshot;
  const menuVersion = typeof menuData['version'] === 'number' ? menuData['version'] : null;
  const pricing = readPricingSettings(pricingSnap.data());

  let newLines: OrderLine[];
  try {
    newLines = calcOrderLines(menu, input.items, GUEST_ORDER_TYPE);
  } catch (err) {
    if (err instanceof PricingError) {
      console.warn(`顧客下單被算價擋下：${err.code} — ${err.message}`);
      throw new HttpsError('failed-precondition', pricingMessage(err));
    }
    throw err;
  }

  const nowTs = Timestamp.fromDate(now);

  return db.runTransaction(async (tx): Promise<GuestOrderView> => {
    // 桌位文件是這段 transaction 的鎖。
    //
    // 直接查「這張桌有沒有 active session」是不夠的：Firestore 的 transaction 不會鎖住
    // 「還不存在的文件」，兩位客人同時按送出時，兩邊的查詢都會回空集合，於是開出兩個
    // session 與兩張單——正是 SPEC 第五節要用 transaction 避免的那件事。
    // 改成在桌位文件上讀寫一個 activeSessionId 指標，兩邊就是實打實的寫入衝突，
    // 後到的那個會重試並讀到前一個寫下的 session。
    const tableSnap = await tx.get(refs.table(table.id));
    if (!tableSnap.exists) {
      throw new HttpsError('permission-denied', '這張 QR code 已經失效，請洽服務人員');
    }
    const activeSessionId = tableSnap.data()?.['activeSessionId'];

    let session: { id: string; orderId: string } | null = null;
    if (typeof activeSessionId === 'string' && activeSessionId.length > 0) {
      const sessionSnap = await tx.get(refs.session(activeSessionId));
      const data = sessionSnap.data();
      // 指標指到已結帳的 session 時當成沒有 active session：closeOrder 會把指標清掉，
      // 這裡是防呆——萬一清掉那一步漏了，結果應該是開一張新單（SPEC 第六節
      // 〈狀態轉換規則〉已結帳 → 用餐中），不是讓這張桌從此點不了餐。
      if (sessionSnap.exists && data?.['status'] === 'active') {
        session = { id: activeSessionId, orderId: String(data['orderId'] ?? '') };
      }
    }

    if (session !== null) {
      const orderRef = refs.order(session.orderId);
      const orderSnap = await tx.get(orderRef);
      if (!orderSnap.exists) {
        throw new HttpsError('internal', '訂單資料異常，請洽服務人員');
      }
      const order = orderSnap.data() ?? {};
      const status = String(order['status'] ?? '');

      // 冪等：同一次送出重複打進來（連按兩下、前端逾時重送）就原樣回傳這張單，
      // 不再加一次品項。鍵是客戶端產生的 requestId，與 order_intents 的 intentId 同一個模式。
      // 這個檢查一定要在 transaction 裡：兩次呼叫同時進來時，在外面比對會兩邊都讀到「還沒用過」。
      const appliedRequestIds = (order['appliedRequestIds'] as string[] | undefined) ?? [];
      if (appliedRequestIds.includes(input.requestId)) {
        return view(orderRef.id, session.id, table.label, status, readStoredLines(order), {
          subtotal: readAmount(order, 'subtotal'),
          serviceCharge: readAmount(order, 'serviceCharge'),
          discount: readAmount(order, 'discount'),
          total: readAmount(order, 'total'),
        });
      }

      if (!APPENDABLE_STATUSES.has(status)) {
        // SPEC 第十三節〈邊界情況〉：同桌有人先結帳、有人還想加點時，訊息必須明確寫
        // 「如需加點請洽服務人員」，不可以只丟「操作失敗」。
        throw new HttpsError('failed-precondition', '本桌已結帳，如需加點請洽服務人員');
      }

      // 用訂單自己的 orderType 重算，不是用 GUEST_ORDER_TYPE：店員可能已經把這張單
      // 轉成外帶（SPEC 第十五節〈型態轉換〉），那就該用外帶價，而不是把單價切回內用。
      const orderType = (order['orderType'] as OrderType | undefined) ?? GUEST_ORDER_TYPE;
      const discount = typeof order['discount'] === 'number' ? (order['discount'] as number) : 0;
      const merged = repriceLines([...readStoredLines(order), ...newLines], orderType);
      const totals = calcOrderTotal(merged, orderType, pricing, discount);

      // 狀態維持不變（SPEC 第五節 createOrder 步驟 4）。已經確認過的單不會因為加點
      // 退回待確認，店員在訂單明細裡看得到新品項。
      tx.update(orderRef, {
        lines: merged.map(toStoredLine),
        subtotal: totals.subtotal,
        serviceCharge: totals.serviceCharge,
        total: totals.total,
        taxSummary: totals.taxSummary,
        appliedRequestIds: FieldValue.arrayUnion(input.requestId),
        updatedAt: nowTs,
      });
      return view(orderRef.id, session.id, table.label, status, merged, totals);
    }

    // 掃碼不開桌，送出才開桌（SPEC 第十三節）。重掃一次看帳單不會憑空開出一張空單，
    // 因為走到這裡一定是有品項要下的。
    //
    // sessionId 是能力憑證：客人的 localStorage 靠它證明「我是這個場次的當事人」，
    // 所以必須是猜不到的 32 碼十六進位，不可以用序號或 auto id。
    const sessionId = randomBytes(16).toString('hex');
    const orderRef = refs.orders.doc();
    const totals = calcOrderTotal(newLines, GUEST_ORDER_TYPE, pricing);

    tx.set(orderRef, {
      orderType: GUEST_ORDER_TYPE,
      pickupCode: null,
      tableIds: [table.id],
      tableLabels: [table.label],
      sessionIds: [sessionId],
      status: 'pending_confirm',
      source: 'guest',
      lines: newLines.map(toStoredLine),
      subtotal: totals.subtotal,
      serviceCharge: totals.serviceCharge,
      discount: totals.discount,
      total: totals.total,
      taxSummary: totals.taxSummary,
      // businessDate 由 closeOrder 在結帳當下寫入，營收歸給結帳那一天（SPEC 第五節）。
      businessDate: null,
      menuVersion,
      // 店員之後用 order_intents 加點到這張單時，applyOrderIntent 會 arrayUnion 進來。
      appliedIntentIds: [],
      appliedRequestIds: [input.requestId],
      createdAt: nowTs,
      updatedAt: nowTs,
      closedAt: null,
      createdBy: uid,
    });
    tx.set(refs.session(sessionId), {
      tableId: table.id,
      status: 'active',
      orderId: orderRef.id,
      openedAt: nowTs,
      closedAt: null,
      readableUntil: null,
    });
    tx.update(refs.table(table.id), { activeSessionId: sessionId });

    return view(orderRef.id, sessionId, table.label, 'pending_confirm', newLines, totals);
  });
}
