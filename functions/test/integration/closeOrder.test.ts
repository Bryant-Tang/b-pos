import { randomBytes, randomUUID } from 'node:crypto';
import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import type { StaffCaller } from '../../src/auth/staffAuth.js';
import { closeOrder } from '../../src/orders/closeOrder.js';
import { confirmGuestOrder } from '../../src/orders/confirmGuestOrder.js';
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
const LATER = new Date(NOW.getTime() + 60 * 60 * 1000);

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

const orderDoc = async (id: string) => (await db.doc(path.order(id)).get()).data();
const archiveDoc = async (id: string) => (await db.doc(path.archivedOrder(id)).get()).data() ?? {};
const receiptDoc = async (id: string) => (await db.doc(path.receipt(id)).get()).data();
const tableDoc = async (id: string) => (await db.doc(path.table(id)).get()).data() ?? {};
const sessionDoc = async (id: string) => (await db.doc(path.session(id)).get()).data() ?? {};

const cash = (received?: number) => ({ method: 'cash' as const, ...(received ? { received } : {}) });

/** 顧客掃某張桌的 QR 點一碗牛肉麵（180），店員確認放行，於是那桌有一張結得了的單。 */
async function openGuestOrder(tableToken = TABLE_TOKEN, guest = GUEST) {
  const created = await createGuestOrder(
    db,
    {
      storeId: STORE,
      tableToken,
      requestId: randomUUID(),
      items: [{ itemId: 'item_beef_noodle', qty: 1, options: [] }],
    },
    guest,
    NOW,
  );
  await confirmGuestOrder(db, { orderId: created.orderId, requestId: randomUUID() }, CLERK, NOW);
  return { orderId: created.orderId, sessionId: created.sessionId };
}

/** 店員手開的單：沒有 session，桌位文件上也沒有 activeSessionId。 */
async function staffOrder() {
  const ref = db.collection(`tenants/${STORE}/orders`).doc();
  await ref.set({
    orderType: 'dine_in',
    tableIds: ['table_2'],
    tableLabels: ['角落'],
    sessionIds: [],
    status: 'open',
    source: 'staff',
    lines: [],
    subtotal: 100,
    serviceCharge: 0,
    discount: 0,
    total: 100,
    taxSummary: null,
    businessDate: null,
    appliedIntentIds: [],
    appliedRequestIds: [],
    createdAt: Timestamp.fromDate(NOW),
    updatedAt: Timestamp.fromDate(NOW),
    closedAt: null,
    createdBy: CLERK.uid,
  });
  return ref.id;
}

describe('closeOrder', () => {
  it('把單搬進 orders_archive 並從 orders 刪掉，總額與營業日一起鎖住', async () => {
    const { orderId } = await openGuestOrder();

    const result = await closeOrder(
      db,
      { orderId, payment: cash(500), requestId: randomUUID() },
      CLERK,
      LATER,
    );

    expect(result.outcome).toBe('closed');
    expect(result.total).toBe(180);
    expect(result.change).toBe(320);
    expect(result.businessDate).toBe('2026-09-20');

    expect(await orderDoc(orderId)).toBeUndefined();
    const archived = await archiveDoc(orderId);
    expect(archived['status']).toBe('closed');
    expect(archived['total']).toBe(180);
    expect(archived['businessDate']).toBe('2026-09-20');
    expect(archived['closedBy']).toBe(CLERK.uid);
    expect(archived['payment']).toEqual({ method: 'cash', received: 500, change: 320 });
    expect((archived['closedAt'] as Timestamp).toDate()).toEqual(LATER);
  });

  it('營業到凌晨的單算前一天（換日時間預設清晨 5 點）', async () => {
    const { orderId } = await openGuestOrder();
    const afterMidnight = new Date('2026-09-21T02:30:00+08:00');

    const result = await closeOrder(
      db,
      { orderId, payment: cash(), requestId: randomUUID() },
      CLERK,
      afterMidnight,
    );

    expect(result.businessDate).toBe('2026-09-20');
  });

  it('產生收據，文件 id 就是客人手機裡那個 sessionId，可讀期三小時', async () => {
    const { orderId, sessionId } = await openGuestOrder();

    const result = await closeOrder(
      db,
      { orderId, payment: cash(200), requestId: randomUUID() },
      CLERK,
      LATER,
    );

    const receipt = await receiptDoc(sessionId);
    expect(receipt).toBeDefined();
    expect(receipt?.['orderId']).toBe(orderId);
    expect(receipt?.['lookupCode']).toBe(result.lookupCode);
    expect(String(result.lookupCode)).toMatch(/^\d{4}$/);
    expect(receipt?.['total']).toBe(180);
    expect(receipt?.['tableLabel']).toBe('窗邊');
    expect(receipt?.['tableIds']).toEqual(['table_1']);
    expect((receipt?.['paidAt'] as Timestamp).toDate()).toEqual(LATER);
    expect((receipt?.['expiresAt'] as Timestamp).toDate()).toEqual(
      new Date(LATER.getTime() + 3 * 3600 * 1000),
    );
  });

  it('場次關掉、桌位的 activeSessionId 清掉', async () => {
    const { orderId, sessionId } = await openGuestOrder();
    expect((await tableDoc('table_1'))['activeSessionId']).toBe(sessionId);

    await closeOrder(db, { orderId, payment: cash(), requestId: randomUUID() }, CLERK, LATER);

    const session = await sessionDoc(sessionId);
    expect(session['status']).toBe('closed');
    expect((session['closedAt'] as Timestamp).toDate()).toEqual(LATER);
    expect((session['readableUntil'] as Timestamp).toDate()).toEqual(
      new Date(LATER.getTime() + 3 * 3600 * 1000),
    );
    expect((await tableDoc('table_1'))['activeSessionId']).toBeNull();
  });

  it('新客人已經坐下時不會把他的桌位清成空桌', async () => {
    // SPEC 第六節〈必須允許的並存狀態〉：同一張桌可以同時有一個剛結帳的 session
    // 和一個新客人的 active session。無條件清指標會把新那一組的桌位弄丟。
    const { orderId } = await openGuestOrder();
    const newSessionId = randomBytes(16).toString('hex');
    await db.doc(path.table('table_1')).update({ activeSessionId: newSessionId });

    await closeOrder(db, { orderId, payment: cash(), requestId: randomUUID() }, CLERK, LATER);

    expect((await tableDoc('table_1'))['activeSessionId']).toBe(newSessionId);
  });

  it('併桌的單結一次，兩桌客人各拿到一份收據，查詢碼相同', async () => {
    const a = await openGuestOrder(TABLE_TOKEN, GUEST);
    const b = await openGuestOrder(TABLE_2_TOKEN, 'uid_guest_2');
    await mergeOrders(
      db,
      { targetOrderId: a.orderId, sourceOrderIds: [b.orderId], requestId: randomUUID() },
      CLERK,
      NOW,
    );

    const result = await closeOrder(
      db,
      { orderId: a.orderId, payment: cash(), requestId: randomUUID() },
      CLERK,
      LATER,
    );

    const first = await receiptDoc(a.sessionId);
    const second = await receiptDoc(b.sessionId);
    expect(first?.['lookupCode']).toBe(result.lookupCode);
    expect(second?.['lookupCode']).toBe(result.lookupCode);
    expect(second?.['total']).toBe(360);
    expect(second?.['tableIds']).toEqual(['table_1', 'table_2']);
    expect((await sessionDoc(b.sessionId))['status']).toBe('closed');
    expect((await tableDoc('table_2'))['activeSessionId']).toBeNull();
  });

  it('同一個 requestId 重送回原本的結果，不會再結一次', async () => {
    // 結完這張單已經不在 orders 了。沒有這道冪等，重送看到的是「找不到這張單」，
    // 店員會以為沒結成功而再結一次。
    const { orderId, sessionId } = await openGuestOrder();
    const requestId = randomUUID();

    const first = await closeOrder(db, { orderId, payment: cash(500), requestId }, CLERK, LATER);
    const again = await closeOrder(db, { orderId, payment: cash(500), requestId }, CLERK, NOW);

    expect(again.outcome).toBe('already_applied');
    expect(again.lookupCode).toBe(first.lookupCode);
    expect(again.businessDate).toBe(first.businessDate);
    expect(again.change).toBe(320);
    expect((await receiptDoc(sessionId))?.['lookupCode']).toBe(first.lookupCode);
    expect((await archiveDoc(orderId))['closedAt']).toEqual(Timestamp.fromDate(LATER));
  });

  it('換一個 requestId 想再結一次會被擋下來', async () => {
    const { orderId } = await openGuestOrder();
    await closeOrder(db, { orderId, payment: cash(), requestId: randomUUID() }, CLERK, LATER);

    await rejectsWith(
      closeOrder(db, { orderId, payment: cash(), requestId: randomUUID() }, CLERK, LATER),
      'failed-precondition',
      '已經結帳',
    );
  });

  it('還沒確認的顧客單不能直接結，要先確認', async () => {
    // 直接結等於讓「店員確認」那道關卡可以被跳過，而廚房根本沒收到這張單。
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

    await rejectsWith(
      closeOrder(
        db,
        { orderId: created.orderId, payment: cash(), requestId: randomUUID() },
        CLERK,
        LATER,
      ),
      'failed-precondition',
      '還沒確認',
    );
    expect((await orderDoc(created.orderId))?.['status']).toBe('pending_confirm');
  });

  it('已併走與已作廢的單各自擋下來', async () => {
    for (const [status, hint] of [
      ['merged', '併進別張單'],
      ['voided', '已經作廢'],
    ] as const) {
      const { orderId } = await openGuestOrder();
      await db.doc(path.order(orderId)).update({ status });

      await rejectsWith(
        closeOrder(db, { orderId, payment: cash(), requestId: randomUUID() }, CLERK, LATER),
        'failed-precondition',
        hint,
      );
      await db.doc(path.table('table_1')).update({ activeSessionId: null });
    }
  });

  it('收到的現金不夠就擋下來', async () => {
    const { orderId } = await openGuestOrder();

    await rejectsWith(
      closeOrder(db, { orderId, payment: cash(100), requestId: randomUUID() }, CLERK, LATER),
      'invalid-argument',
      '180',
    );
    expect(await orderDoc(orderId)).toBeDefined();
  });

  it('店員手開、沒有 session 的單結得掉，只是沒有收據', async () => {
    const orderId = await staffOrder();

    const result = await closeOrder(
      db,
      { orderId, payment: { method: 'card' }, requestId: randomUUID() },
      CLERK,
      LATER,
    );

    expect(result.outcome).toBe('closed');
    expect(result.change).toBe(0);
    expect((await archiveDoc(orderId))['payment']).toEqual({ method: 'card', change: 0 });
    expect(await orderDoc(orderId)).toBeUndefined();
  });

  it('順手刪掉這位顧客的限流文件', async () => {
    const { orderId } = await openGuestOrder();
    expect((await db.doc(path.rateLimit(GUEST)).get()).exists).toBe(true);

    await closeOrder(db, { orderId, payment: cash(), requestId: randomUUID() }, CLERK, LATER);

    expect((await db.doc(path.rateLimit(GUEST)).get()).exists).toBe(false);
  });

  it('找不到的單回 not-found，別家店的店員也一樣', async () => {
    const { orderId } = await openGuestOrder();

    await rejectsWith(
      closeOrder(
        db,
        { orderId: 'order_nope', payment: cash(), requestId: randomUUID() },
        CLERK,
        LATER,
      ),
      'not-found',
    );
    await rejectsWith(
      closeOrder(
        db,
        { orderId, payment: cash(), requestId: randomUUID() },
        { uid: 'uid_clerk_other', storeId: 'store_other', role: 'staff' },
        LATER,
      ),
      'not-found',
    );
    expect(await orderDoc(orderId)).toBeDefined();
  });
});
