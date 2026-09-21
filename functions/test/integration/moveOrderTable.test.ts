import { randomUUID } from 'node:crypto';
import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import type { StaffCaller } from '../../src/auth/staffAuth.js';
import { createGuestOrder } from '../../src/orders/createGuestOrder.js';
import { moveOrderTable } from '../../src/orders/moveOrderTable.js';
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
const TABLE_3 = { ...TABLE, label: '包廂', sort: 2, qrToken: 'aaaabbbbccccddddaaaabbbbccccdddd' };

beforeEach(async () => {
  await clearFirestore();
  await db.doc(path.publishedMenu).set(MENU);
  await db.doc(path.table('table_1')).set(TABLE);
  await db.doc(path.table('table_2')).set(TABLE_2);
  await db.doc(path.table('table_3')).set(TABLE_3);
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

/** 顧客掃 table_1 的 QR 點一碗牛肉麵，於是 table_1 上有一攤（含 session）。 */
async function guestOrderOnTable1() {
  const created = await createGuestOrder(
    db,
    {
      storeId: STORE,
      tableToken: TABLE_TOKEN,
      requestId: randomUUID(),
      items: [{ itemId: 'item_beef_noodle', qty: 1, options: [] }],
    },
    GUEST,
    NOW,
  );
  return { orderId: created.orderId, sessionId: created.sessionId };
}

/**
 * 店員手開的單：沒有 session，桌位文件上也沒有 activeSessionId。
 * applyOrderIntent 建出來的就是這個形狀，這裡直接寫是為了不必為了一張單跑整條意圖流程。
 */
async function staffOrderOn(tableId: string, label: string) {
  const ref = db.collection(`tenants/${STORE}/orders`).doc();
  await ref.set({
    orderType: 'dine_in',
    tableIds: [tableId],
    tableLabels: [label],
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

const move = (orderId: string, from: string, to: string, requestId = randomUUID()) =>
  moveOrderTable(db, { orderId, fromTableId: from, toTableId: to, requestId }, CLERK, LATER);

describe('moveOrderTable', () => {
  it('把單上的桌換成新的那一桌', async () => {
    const { orderId } = await guestOrderOnTable1();

    const result = await move(orderId, 'table_1', 'table_2');

    expect(result.outcome).toBe('moved');
    expect(result.tableIds).toEqual(['table_2']);
    // 標籤也要換。訂單明細與出單都是讀這個快照，不換的話新桌上印出來的是舊桌號。
    expect(result.tableLabels).toEqual(['角落']);
    const order = await orderDoc(orderId);
    expect(order['tableIds']).toEqual(['table_2']);
    expect(order['tableLabels']).toEqual(['角落']);
  });

  it('session 跟著搬，兩張桌的 activeSessionId 也跟著換', async () => {
    const { orderId, sessionId } = await guestOrderOnTable1();
    expect((await tableDoc('table_1'))['activeSessionId']).toBe(sessionId);

    await move(orderId, 'table_1', 'table_2');

    // 客人手機裡存的還是同一個 sessionId（SPEC 第十三節：權限掛在 sessionId 上），
    // 所以他在新桌重新整理就看得到自己的單。
    expect((await sessionDoc(sessionId))['tableId']).toBe('table_2');
    expect((await tableDoc('table_2'))['activeSessionId']).toBe(sessionId);
    // 舊桌一定要清空，否則下一組客人掃進來會加點到已經搬走的那一攤帳上。
    expect((await tableDoc('table_1'))['activeSessionId']).toBeNull();
  });

  it('留下誰轉的痕跡', async () => {
    const { orderId } = await guestOrderOnTable1();

    await move(orderId, 'table_1', 'table_2');

    expect((await orderDoc(orderId))['lastMovedBy']).toBe(CLERK.uid);
  });

  it('同一個 requestId 重送回到同一個答案，不會再搬一次', async () => {
    const { orderId } = await guestOrderOnTable1();
    const requestId = randomUUID();

    await move(orderId, 'table_1', 'table_2', requestId);
    // 重送時單已經不在 table_1 上了。沒有冪等的話這裡會是「這張單不在那一桌」，
    // 店員看到失敗訊息會再轉一次——那次是從對的桌轉到別處。
    const again = await move(orderId, 'table_1', 'table_2', requestId);

    expect(again.outcome).toBe('already_applied');
    expect(again.tableIds).toEqual(['table_2']);
  });

  it('目標桌還有人在用就擋下來', async () => {
    const { orderId } = await guestOrderOnTable1();
    await staffOrderOn('table_2', '角落');

    await rejectsWith(move(orderId, 'table_1', 'table_2'), 'failed-precondition', '還有客人在用');

    // 擋下來就不能留下半套：單還在原桌，原桌的指標也還在。
    expect((await orderDoc(orderId))['tableIds']).toEqual(['table_1']);
    expect((await tableDoc('table_1'))['activeSessionId']).not.toBeNull();
  });

  it('目標桌只有已結帳的單時可以轉過去', async () => {
    // 結帳後的單會留在 orders 裡等 releaseTables 清（SPEC 第十三節〈自動清桌〉）。
    // 看到有單就擋的話，打烊前每一張桌都會變成轉不過去。
    const { orderId } = await guestOrderOnTable1();
    const closed = await staffOrderOn('table_2', '角落');
    await db.doc(path.order(closed)).update({ status: 'closed' });

    const result = await move(orderId, 'table_1', 'table_2');

    expect(result.outcome).toBe('moved');
  });

  it('店員手開、沒有 session 的單也轉得動', async () => {
    const orderId = await staffOrderOn('table_1', '窗邊');

    const result = await move(orderId, 'table_1', 'table_2');

    expect(result.tableIds).toEqual(['table_2']);
    // 沒有 session 就不該憑空生一個指標出來，否則桌位總覽會把空桌畫成用餐中。
    expect((await tableDoc('table_2'))['activeSessionId']).toBeUndefined();
  });

  it('併過桌的單只搬指名的那一桌，另一桌留著', async () => {
    const { orderId } = await guestOrderOnTable1();
    await db
      .doc(path.order(orderId))
      .update({ tableIds: ['table_1', 'table_2'], tableLabels: ['窗邊', '角落'] });

    const result = await move(orderId, 'table_2', 'table_3');

    expect(result.tableIds).toEqual(['table_1', 'table_3']);
    expect(result.tableLabels).toEqual(['窗邊', '包廂']);
  });

  it('來源桌不在這張單上就擋下來', async () => {
    const { orderId } = await guestOrderOnTable1();

    await rejectsWith(move(orderId, 'table_3', 'table_2'), 'failed-precondition', '不在那一桌');
  });

  it('目標桌已經在同一張單上就擋下來', async () => {
    // 允許的話等於悄悄把併桌拆掉：那一桌從單上消失、總覽變成空桌，但客人還坐著。
    const { orderId } = await guestOrderOnTable1();
    await db
      .doc(path.order(orderId))
      .update({ tableIds: ['table_1', 'table_2'], tableLabels: ['窗邊', '角落'] });

    await rejectsWith(move(orderId, 'table_1', 'table_2'), 'failed-precondition', '已經在這張單上');
  });

  it('已結帳與已作廢的單都不能轉', async () => {
    const { orderId } = await guestOrderOnTable1();

    await db.doc(path.order(orderId)).update({ status: 'closed' });
    await rejectsWith(move(orderId, 'table_1', 'table_2'), 'failed-precondition', '已經結帳');

    await db.doc(path.order(orderId)).update({ status: 'voided' });
    await rejectsWith(move(orderId, 'table_1', 'table_2'), 'failed-precondition', '已經作廢');
  });

  it('找不到的單與找不到的桌分別回報', async () => {
    const { orderId } = await guestOrderOnTable1();

    await rejectsWith(move('order_missing', 'table_1', 'table_2'), 'not-found', '找不到這張單');
    await rejectsWith(move(orderId, 'table_1', 'table_missing'), 'not-found', '找不到那一桌');
  });

  it('封存的桌不能當目標', async () => {
    const { orderId } = await guestOrderOnTable1();
    await db.doc(path.table('table_2')).update({ archived: true });

    await rejectsWith(move(orderId, 'table_1', 'table_2'), 'not-found', '找不到那一桌');
  });

  it('來源與目標同一桌直接擋掉，不當成成功', async () => {
    const { orderId } = await guestOrderOnTable1();

    await rejectsWith(move(orderId, 'table_1', 'table_1'), 'invalid-argument', '同一桌');
  });

  it('外帶單沒有桌可以轉', async () => {
    const ref = db.collection(`tenants/${STORE}/orders`).doc();
    await ref.set({
      orderType: 'takeout',
      tableIds: [],
      tableLabels: [],
      sessionIds: [],
      status: 'open',
      lines: [],
      appliedRequestIds: [],
    });

    await rejectsWith(move(ref.id, 'table_1', 'table_2'), 'failed-precondition', '不在那一桌');
  });
});
