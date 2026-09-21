import { randomUUID } from 'node:crypto';
import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import type { StaffCaller } from '../../src/auth/staffAuth.js';
import { createGuestOrder } from '../../src/orders/createGuestOrder.js';
import { mergeOrders } from '../../src/orders/mergeOrders.js';
import {
  MENU,
  STORE,
  TABLE,
  TABLE_TOKEN,
  adminDb,
  clearFirestore,
  disposeAdminDb,
  path,
} from './helpers.js';

const db: Firestore = adminDb();

const NOW = new Date('2026-09-20T19:00:00+08:00');
const LATER = new Date(NOW.getTime() + 10 * 60 * 1000);

/** 虛構的員工帳號（見 CLAUDE.md 第一節）。 */
const CLERK: StaffCaller = { uid: 'uid_clerk_1', storeId: STORE, role: 'staff' };
const GUEST = 'uid_guest_1';

/** 第二張桌。token 與 helpers 那張不同，否則 findTableByToken 會查到兩筆。 */
const TABLE_2_TOKEN = 'fedcba9876543210fedcba9876543210';
const TABLE_2 = { ...TABLE, label: '角落', sort: 1, qrToken: TABLE_2_TOKEN };

beforeEach(async () => {
  await clearFirestore();
  await db.doc(path.publishedMenu).set(MENU);
  await db.doc(path.table('table_1')).set(TABLE);
  await db.doc(path.table('table_2')).set(TABLE_2);
});

afterAll(async () => {
  await disposeAdminDb();
});

const codeOf = (err: unknown) => (err as { code?: string }).code;

async function rejectsWith(promise: Promise<unknown>, code: string, message?: string) {
  await expect(promise).rejects.toSatisfy((err: unknown) => {
    expect(codeOf(err)).toBe(code);
    if (message !== undefined) expect(String((err as Error).message)).toContain(message);
    return true;
  });
}

const orderDoc = async (id: string) => (await db.doc(path.order(id)).get()).data() ?? {};
const tableDoc = async (id: string) => (await db.doc(path.table(id)).get()).data() ?? {};
const sessionDoc = async (id: string) => (await db.doc(path.session(id)).get()).data() ?? {};

interface StoredLineDoc {
  lineId: string;
  name: string;
  subtotal: number;
}

const linesOf = async (id: string) => ((await orderDoc(id))['lines'] ?? []) as StoredLineDoc[];

/** 顧客掃某一桌的 QR 點東西，於是那一桌有一攤（含 session）。牛肉麵 180、珍奶 60。 */
async function guestOrder(tableToken: string, itemId: string) {
  const created = await createGuestOrder(
    db,
    {
      storeId: STORE,
      tableToken,
      requestId: randomUUID(),
      items: [{ itemId, qty: 1, options: [] }],
    },
    GUEST,
    NOW,
  );
  return { orderId: created.orderId, sessionId: created.sessionId };
}

/** 兩桌各一攤：table_1 點牛肉麵 180，table_2 點珍珠奶茶 60。 */
async function twoTables() {
  const a = await guestOrder(TABLE_TOKEN, 'item_beef_noodle');
  const b = await guestOrder(TABLE_2_TOKEN, 'item_bubble_tea');
  return { a, b };
}

const merge = (targetOrderId: string, sourceOrderIds: string[], requestId = randomUUID()) =>
  mergeOrders(db, { targetOrderId, sourceOrderIds, requestId }, CLERK, LATER);

describe('mergeOrders', () => {
  it('品項併到目標單上，金額重算', async () => {
    const { a, b } = await twoTables();

    const result = await merge(a.orderId, [b.orderId]);

    expect(result.outcome).toBe('merged');
    expect(result.subtotal).toBe(240);
    expect(result.total).toBe(240);
    const names = (await linesOf(a.orderId)).map((line) => line.name);
    expect(names).toEqual(['牛肉麵', '珍珠奶茶']);
  });

  it('服務費是用併完的品項重算，不是兩張單的服務費相加', async () => {
    // 兩張各 180 與 60，10% 服務費分別是 18 與 6。相加剛好也是 24，看不出差別，
    // 所以這裡用會產生進位差的數字：55 與 55 各算 5.5 → 進位各 6，相加是 12，
    // 但併完 110 的 10% 是 11。相加的寫法會多收一塊錢。
    await db.doc(path.pricingSettings).set({ dineInServiceCharge: 0.1 });
    const a = await guestOrder(TABLE_TOKEN, 'item_bubble_tea');
    const b = await guestOrder(TABLE_2_TOKEN, 'item_bubble_tea');
    await db.doc(path.order(a.orderId)).update({ lines: fixedPrice(await linesOf(a.orderId), 55) });
    await db.doc(path.order(b.orderId)).update({ lines: fixedPrice(await linesOf(b.orderId), 55) });

    const result = await merge(a.orderId, [b.orderId]);

    expect(result.subtotal).toBe(110);
    expect(result.serviceCharge).toBe(11);
    expect(result.total).toBe(121);
  });

  it('桌位取聯集，桌號標籤跟著對位', async () => {
    const { a, b } = await twoTables();

    const result = await merge(a.orderId, [b.orderId]);

    expect(result.tableIds).toEqual(['table_1', 'table_2']);
    expect(result.tableLabels).toEqual(['窗邊', '角落']);
  });

  it('來源單本來就有兩桌時，每個桌號還是對到自己的標籤', async () => {
    // 併過一次的單會有兩桌以上。標籤是跟 tableIds 平行的陣列，補的時候取錯位置
    // 不會報錯，只會讓併完的單上寫著另一桌的桌號——店員照著送錯桌。
    const { a, b } = await twoTables();
    await db
      .doc(path.order(b.orderId))
      .update({ tableIds: ['table_2', 'table_9'], tableLabels: ['角落', '包廂'] });

    const result = await merge(a.orderId, [b.orderId]);

    expect(result.tableIds).toEqual(['table_1', 'table_2', 'table_9']);
    expect(result.tableLabels).toEqual(['窗邊', '角落', '包廂']);
  });

  it('來源單變成 merged，指回目標單，桌位交出去但留得住紀錄', async () => {
    const { a, b } = await twoTables();

    await merge(a.orderId, [b.orderId]);

    const source = await orderDoc(b.orderId);
    expect(source['status']).toBe('merged');
    expect(source['mergedInto']).toBe(a.orderId);
    // 桌位要清掉，否則平板的「這張桌的訂單」會同時列出目標單與一張空殼。
    expect(source['tableIds']).toEqual([]);
    // 但原本在哪一桌要查得到，這是之後對帳唯一的線索。
    expect(source['tableIdsBeforeMerge']).toEqual(['table_2']);
    expect((await orderDoc(a.orderId))['mergedFrom']).toEqual([b.orderId]);
  });

  it('來源單的 session 指到目標單，桌位文件的指標不動', async () => {
    // SPEC 第十三節第 4 點：併桌後兩張 QR 都指向同一張單，客人無感。
    const { a, b } = await twoTables();

    await merge(a.orderId, [b.orderId]);

    expect((await sessionDoc(b.sessionId))['orderId']).toBe(a.orderId);
    expect((await sessionDoc(a.sessionId))['orderId']).toBe(a.orderId);
    // 兩張桌各自還是指著自己的 session，所以客人掃哪一張 QR 都認得出自己，
    // 只是兩個 session 現在走到同一張帳單。
    expect((await tableDoc('table_1'))['activeSessionId']).toBe(a.sessionId);
    expect((await tableDoc('table_2'))['activeSessionId']).toBe(b.sessionId);
    expect((await orderDoc(a.orderId))['sessionIds']).toEqual([a.sessionId, b.sessionId]);
  });

  it('折扣相加，不會因為併單消失', async () => {
    const { a, b } = await twoTables();
    await db.doc(path.order(a.orderId)).update({ discount: 20 });
    await db.doc(path.order(b.orderId)).update({ discount: 10 });

    const result = await merge(a.orderId, [b.orderId]);

    expect(result.discount).toBe(30);
    expect(result.total).toBe(210);
  });

  it('來源單的意圖與請求 id 一起接手', async () => {
    // 不接手的話，那些意圖重送時會被當成沒套用過，在目標單上再加一次同樣的品項。
    const { a, b } = await twoTables();
    await db.doc(path.order(b.orderId)).update({ appliedIntentIds: ['intent_b'] });
    const bRequestIds = (await orderDoc(b.orderId))['appliedRequestIds'] as string[];

    await merge(a.orderId, [b.orderId]);

    const target = await orderDoc(a.orderId);
    expect(target['appliedIntentIds']).toContain('intent_b');
    expect(target['appliedRequestIds']).toEqual(expect.arrayContaining(bRequestIds));
  });

  it('一次併三張', async () => {
    const { a, b } = await twoTables();
    const c = await staffOrder();

    const result = await merge(a.orderId, [b.orderId, c]);

    expect(result.mergedOrderIds).toEqual([b.orderId, c]);
    expect((await orderDoc(c))['status']).toBe('merged');
  });

  it('同一個 requestId 重送回到同一個答案，不會再併一次', async () => {
    const { a, b } = await twoTables();
    const requestId = randomUUID();

    const first = await merge(a.orderId, [b.orderId], requestId);
    // 重送時來源單已經是 merged。沒有冪等的話這裡會是「已經併進別張單了」，
    // 店員看到失敗訊息，很可能改去併別張——而品項已經在目標單上了。
    const again = await merge(a.orderId, [b.orderId], requestId);

    expect(again.outcome).toBe('already_applied');
    expect(again.total).toBe(first.total);
    expect(await linesOf(a.orderId)).toHaveLength(2);
  });

  it('內用單與外帶單不能併在一起', async () => {
    // 內用與外帶的單價是兩個欄位，服務費也只有內用收。混著算不會報錯，只會安靜收錯錢。
    const { a, b } = await twoTables();
    await db.doc(path.order(b.orderId)).update({ orderType: 'takeout' });

    await rejectsWith(merge(a.orderId, [b.orderId]), 'failed-precondition', '不能併在一起');
  });

  it('已結帳、已作廢、已併過的單都不能再併', async () => {
    const { a, b } = await twoTables();

    for (const [status, message] of [
      ['closed', '已經結帳'],
      ['voided', '已經作廢'],
      ['merged', '已經併進別張單'],
    ] as const) {
      await db.doc(path.order(b.orderId)).update({ status });
      await rejectsWith(merge(a.orderId, [b.orderId]), 'failed-precondition', message);
    }
  });

  it('目標單已經結帳也不能併', async () => {
    const { a, b } = await twoTables();
    await db.doc(path.order(a.orderId)).update({ status: 'closed' });

    await rejectsWith(merge(a.orderId, [b.orderId]), 'failed-precondition', '已經結帳');
  });

  it('擋下來的時候一張單都不會被改到', async () => {
    const { a, b } = await twoTables();
    const c = await staffOrder();
    await db.doc(path.order(c)).update({ status: 'closed' });

    await rejectsWith(merge(a.orderId, [b.orderId, c]), 'failed-precondition', '已經結帳');

    // b 是合法的來源，但整批不成立就不該有半套結果。
    expect((await orderDoc(b.orderId))['status']).toBe('pending_confirm');
    expect(await linesOf(a.orderId)).toHaveLength(1);
  });

  it('找不到的單分別回報', async () => {
    const { a, b } = await twoTables();

    await rejectsWith(merge('order_missing', [b.orderId]), 'not-found', '找不到要留下來的');
    await rejectsWith(merge(a.orderId, ['order_missing']), 'not-found', '有一張要併進來的單');
  });
});

/** 店員手開的單：沒有 session，桌位文件上也沒有 activeSessionId。 */
async function staffOrder() {
  const ref = db.collection(`tenants/${STORE}/orders`).doc();
  await ref.set({
    orderType: 'dine_in',
    tableIds: [],
    tableLabels: [],
    sessionIds: [],
    status: 'open',
    source: 'staff',
    lines: [],
    subtotal: 0,
    serviceCharge: 0,
    discount: 0,
    total: 0,
    appliedIntentIds: [],
    appliedRequestIds: [],
    createdAt: Timestamp.fromDate(NOW),
    updatedAt: Timestamp.fromDate(NOW),
    closedAt: null,
    createdBy: CLERK.uid,
  });
  return ref.id;
}

/** 把每一行的小計與單價改成指定的整數，用來造出會產生進位差的金額。 */
function fixedPrice(lines: StoredLineDoc[], price: number) {
  return lines.map((line) => ({
    ...line,
    unitPriceDineIn: price,
    unitPriceTakeout: price,
    subtotal: price,
  }));
}
