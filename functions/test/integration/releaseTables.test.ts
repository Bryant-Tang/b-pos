import { randomUUID } from 'node:crypto';
import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import type { StaffCaller } from '../../src/auth/staffAuth.js';
import { closeOrder } from '../../src/orders/closeOrder.js';
import { confirmGuestOrder } from '../../src/orders/confirmGuestOrder.js';
import { createGuestOrder } from '../../src/orders/createGuestOrder.js';
import { mergeOrders } from '../../src/orders/mergeOrders.js';
import { releaseExpiredTables } from '../../src/orders/releaseTables.js';
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
/** 收據與已結帳場次的可讀期是三小時（closeOrder 的 READABLE_HOURS）。 */
const EXPIRES_AT = new Date(NOW.getTime() + 3 * 60 * 60 * 1000);
const ONE_MS_BEFORE = new Date(EXPIRES_AT.getTime() - 1);

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

const sessionExists = async (id: string) => (await db.doc(path.session(id)).get()).exists;
const receiptExists = async (id: string) => (await db.doc(path.receipt(id)).get()).exists;
const tableDoc = async (id: string) => (await db.doc(path.table(id)).get()).data() ?? {};

/** 顧客掃某張桌的 QR 點一碗牛肉麵（180），店員確認放行。 */
async function openGuestOrder(tableToken = TABLE_TOKEN, guest = GUEST, at = NOW) {
  const created = await createGuestOrder(
    db,
    {
      storeId: STORE,
      tableToken,
      requestId: randomUUID(),
      items: [{ itemId: 'item_beef_noodle', qty: 1, options: [] }],
    },
    guest,
    at,
  );
  await confirmGuestOrder(db, { orderId: created.orderId, requestId: randomUUID() }, CLERK, at);
  return { orderId: created.orderId, sessionId: created.sessionId };
}

/** 點一張單、結掉，於是有一個可讀期到 EXPIRES_AT 為止的已結帳場次。 */
async function closedSession(tableToken = TABLE_TOKEN, guest = GUEST) {
  const { orderId, sessionId } = await openGuestOrder(tableToken, guest);
  await closeOrder(
    db,
    { orderId, payment: { method: 'cash', received: 200 }, requestId: randomUUID() },
    CLERK,
    NOW,
  );
  return { orderId, sessionId };
}

describe('releaseExpiredTables', () => {
  it('可讀期一到就把已結帳的場次與收據收掉', async () => {
    const { sessionId } = await closedSession();

    const result = await releaseExpiredTables(db, EXPIRES_AT);

    expect(result).toMatchObject({
      scanned: 1,
      releasedSessions: 1,
      deletedReceipts: 1,
      keptSessions: 0,
      clearedTables: 0,
    });
    expect(await sessionExists(sessionId)).toBe(false);
    expect(await receiptExists(sessionId)).toBe(false);
  });

  it('差一毫秒還在可讀期，一個都不動', async () => {
    // 平板判斷「還看得到帳單」用的是 readableUntil > now，這裡必須是同一邊，
    // 否則會有一分鐘平板顯示已結帳、伺服器已經把場次刪掉。
    const { sessionId } = await closedSession();

    const result = await releaseExpiredTables(db, ONE_MS_BEFORE);

    expect(result.scanned).toBe(0);
    expect(await sessionExists(sessionId)).toBe(true);
    expect(await receiptExists(sessionId)).toBe(true);
  });

  it('還在用餐中的場次不會被收（沒有 readableUntil）', async () => {
    const { sessionId } = await openGuestOrder();

    // 隔天中午跑都一樣：active 的場次根本不在查詢範圍裡。
    const result = await releaseExpiredTables(db, new Date('2026-09-21T12:00:00+08:00'));

    expect(result.scanned).toBe(0);
    expect(await sessionExists(sessionId)).toBe(true);
  });

  it('併桌結一次的兩個場次與兩份收據一起收掉', async () => {
    const first = await openGuestOrder(TABLE_TOKEN, GUEST);
    const second = await openGuestOrder(TABLE_2_TOKEN, 'uid_guest_2');
    const merged = await mergeOrders(
      db,
      { targetOrderId: first.orderId, sourceOrderIds: [second.orderId], requestId: randomUUID() },
      CLERK,
      NOW,
    );
    await closeOrder(
      db,
      { orderId: merged.targetOrderId, payment: { method: 'card' }, requestId: randomUUID() },
      CLERK,
      NOW,
    );

    const result = await releaseExpiredTables(db, EXPIRES_AT);

    expect(result).toMatchObject({ scanned: 2, releasedSessions: 2, deletedReceipts: 2 });
    expect(await sessionExists(first.sessionId)).toBe(false);
    expect(await sessionExists(second.sessionId)).toBe(false);
    expect(await receiptExists(first.sessionId)).toBe(false);
    expect(await receiptExists(second.sessionId)).toBe(false);
  });

  it('清掉還指著過期場次的桌位指標', async () => {
    const { sessionId } = await closedSession();
    // closeOrder 結帳當下就把指標清掉了，這裡人為裝回去：模擬指標因為任何理由
    // 沒被清到。刪掉場次之後留一個指向不存在文件的指標，是下一個讀的人最難查的那種資料。
    await db.doc(path.table('table_1')).update({ activeSessionId: sessionId });

    const result = await releaseExpiredTables(db, EXPIRES_AT);

    expect(result.clearedTables).toBe(1);
    expect((await tableDoc('table_1'))['activeSessionId']).toBeNull();
  });

  it('桌位指標指著新客人的場次時不動它', async () => {
    const closed = await closedSession();
    // 前一組客人還在門口看帳單，新客人已經坐下點了（SPEC 第六節〈必須允許的並存狀態〉）。
    const fresh = await openGuestOrder(TABLE_TOKEN, 'uid_guest_2', new Date(NOW.getTime() + 60_000));
    expect((await tableDoc('table_1'))['activeSessionId']).toBe(fresh.sessionId);

    const result = await releaseExpiredTables(db, EXPIRES_AT);

    expect(result.releasedSessions).toBe(1);
    expect(result.clearedTables).toBe(0);
    expect(await sessionExists(closed.sessionId)).toBe(false);
    expect((await tableDoc('table_1'))['activeSessionId']).toBe(fresh.sessionId);
    expect(await sessionExists(fresh.sessionId)).toBe(true);
  });

  it('收據還看得到的話，連同場次一起留到下一輪', async () => {
    const { sessionId } = await closedSession();
    // 兩邊的三小時是 closeOrder 在同一個 transaction 裡寫的同一個時間，正常不會不一致；
    // 人為拉開是為了確保真的不一致時，被留下的是「客人還看得到的那一份」。
    await db
      .doc(path.receipt(sessionId))
      .update({ expiresAt: Timestamp.fromDate(new Date(EXPIRES_AT.getTime() + 3600_000)) });

    const result = await releaseExpiredTables(db, EXPIRES_AT);

    expect(result).toMatchObject({ scanned: 1, releasedSessions: 0, keptSessions: 1 });
    expect(await sessionExists(sessionId)).toBe(true);
    expect(await receiptExists(sessionId)).toBe(true);
  });

  it('沒有收據的場次也收得掉', async () => {
    const { sessionId } = await closedSession();
    await db.doc(path.receipt(sessionId)).delete();

    const result = await releaseExpiredTables(db, EXPIRES_AT);

    expect(result).toMatchObject({ releasedSessions: 1, deletedReceipts: 0 });
    expect(await sessionExists(sessionId)).toBe(false);
  });

  it('一輪吃不完的留到下一輪，沒有任何一個會被漏掉', async () => {
    const first = await closedSession(TABLE_TOKEN, GUEST);
    const second = await closedSession(TABLE_2_TOKEN, 'uid_guest_2');

    const firstRun = await releaseExpiredTables(db, EXPIRES_AT, 1);
    expect(firstRun.releasedSessions).toBe(1);

    const secondRun = await releaseExpiredTables(db, EXPIRES_AT, 1);
    expect(secondRun.releasedSessions).toBe(1);

    expect(await sessionExists(first.sessionId)).toBe(false);
    expect(await sessionExists(second.sessionId)).toBe(false);
  });

  it('再跑一次什麼都不做', async () => {
    await closedSession();
    await releaseExpiredTables(db, EXPIRES_AT);

    expect(await releaseExpiredTables(db, EXPIRES_AT)).toMatchObject({
      scanned: 0,
      releasedSessions: 0,
    });
  });

  it('每一份文件都寫回它自己那間店', async () => {
    // 跨店一次查完（collectionGroup），所以要確認刪的是同一間店底下的收據，
    // 不是拿 sessionId 到別間店去刪。第二間店同樣是虛構的（見 CLAUDE.md 第一節）。
    const other = 'store_demo_2';
    const sharedId = 'session_shared_id';
    await db.doc(`tenants/${other}/sessions/${sharedId}`).set({
      tableId: 'table_9',
      status: 'closed',
      orderId: 'order_other',
      openedAt: Timestamp.fromDate(NOW),
      closedAt: Timestamp.fromDate(NOW),
      readableUntil: Timestamp.fromDate(EXPIRES_AT),
    });
    await db
      .doc(`tenants/${other}/receipts/${sharedId}`)
      .set({ orderId: 'order_other', total: 180, expiresAt: Timestamp.fromDate(EXPIRES_AT) });
    // 同一個 id 在本店底下還在可讀期，不該被別間店的那一輪連帶刪掉。
    await db
      .doc(path.receipt(sharedId))
      .set({ orderId: 'order_here', total: 180, expiresAt: Timestamp.fromDate(EXPIRES_AT) });

    const result = await releaseExpiredTables(db, EXPIRES_AT);

    expect(result).toMatchObject({ scanned: 1, releasedSessions: 1, deletedReceipts: 1 });
    expect((await db.doc(`tenants/${other}/sessions/${sharedId}`).get()).exists).toBe(false);
    expect((await db.doc(`tenants/${other}/receipts/${sharedId}`).get()).exists).toBe(false);
    expect(await receiptExists(sharedId)).toBe(true);
  });
});
