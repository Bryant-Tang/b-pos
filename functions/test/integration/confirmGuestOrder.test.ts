import { randomUUID } from 'node:crypto';
import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import type { StaffCaller } from '../../src/auth/staffAuth.js';
import { confirmGuestOrder } from '../../src/orders/confirmGuestOrder.js';
import { createGuestOrder } from '../../src/orders/createGuestOrder.js';
import { getTableState } from '../../src/orders/getTableState.js';
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
const OTHER_CLERK: StaffCaller = { uid: 'uid_clerk_2', storeId: STORE, role: 'staff' };
const GUEST = 'uid_guest_1';

beforeEach(async () => {
  await clearFirestore();
  await db.doc(path.publishedMenu).set(MENU);
  await db.doc(path.table('table_1')).set(TABLE);
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

/** 顧客掃 QR 點一碗牛肉麵。建出來的單一定是 pending_confirm。 */
async function guestOrder() {
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

describe('confirmGuestOrder', () => {
  it('把顧客自助單從 pending_confirm 放行成 open，並留下是誰在什麼時候按的', async () => {
    const { orderId } = await guestOrder();
    expect((await orderDoc(orderId))['status']).toBe('pending_confirm');

    const result = await confirmGuestOrder(db, { orderId, requestId: randomUUID() }, CLERK, LATER);

    expect(result.outcome).toBe('confirmed');
    expect(result.status).toBe('open');
    const order = await orderDoc(orderId);
    expect(order['status']).toBe('open');
    expect(order['confirmedBy']).toBe(CLERK.uid);
    expect((order['confirmedAt'] as Timestamp).toDate()).toEqual(LATER);
  });

  it('確認不會動到桌位的 activeSessionId 與 session 的狀態', async () => {
    // 顧客端判斷「這攤還是不是我的」就是靠這兩個（web/guest 的 session.ts
    // 與 getTableState 的 mine）。放行只是讓這張單進廚房，不是換一攤客人。
    const { orderId, sessionId } = await guestOrder();

    await confirmGuestOrder(db, { orderId, requestId: randomUUID() }, CLERK, LATER);

    expect((await tableDoc('table_1'))['activeSessionId']).toBe(sessionId);
    const session = await sessionDoc(sessionId);
    expect(session['status']).toBe('active');
    expect(session['orderId']).toBe(orderId);
  });

  it('確認之後客人掃 QR 還是看得到自己那一攤的份數與金額', async () => {
    const { orderId, sessionId } = await guestOrder();
    await confirmGuestOrder(db, { orderId, requestId: randomUUID() }, CLERK, LATER);

    const state = await getTableState(
      db,
      { storeId: STORE, tableToken: TABLE_TOKEN, sessionId },
      GUEST,
      LATER,
    );

    expect(state.openOrder).not.toBeNull();
    expect(state.openOrder?.itemCount).toBe(1);
    expect(state.openOrder?.total).toBe(180);
    expect(state.openOrder?.mine).toBe(true);
    expect(orderId).not.toBe('');
  });

  it('同一個 requestId 重送不會再放行一次', async () => {
    // 這支一成功平板就會印廚房單。第一次成功但回應掉了、店員再按一次時，
    // 沒有這道冪等，廚房會收到兩張一樣的單。
    const { orderId } = await guestOrder();
    const requestId = randomUUID();

    const first = await confirmGuestOrder(db, { orderId, requestId }, CLERK, LATER);
    const again = await confirmGuestOrder(db, { orderId, requestId }, CLERK, new Date());

    expect(first.outcome).toBe('confirmed');
    expect(again.outcome).toBe('already_applied');
    expect(again.status).toBe('open');
    const order = await orderDoc(orderId);
    expect((order['confirmedAt'] as Timestamp).toDate()).toEqual(LATER);
    expect(order['confirmedBy']).toBe(CLERK.uid);
  });

  it('別台平板先按過了就回 already_open，不是丟錯，也不會蓋掉原本的紀錄', async () => {
    const { orderId } = await guestOrder();
    await confirmGuestOrder(db, { orderId, requestId: randomUUID() }, CLERK, LATER);

    const second = await confirmGuestOrder(
      db,
      { orderId, requestId: randomUUID() },
      OTHER_CLERK,
      new Date(),
    );

    expect(second.outcome).toBe('already_open');
    expect(second.status).toBe('open');
    const order = await orderDoc(orderId);
    expect(order['confirmedBy']).toBe(CLERK.uid);
    expect((order['confirmedAt'] as Timestamp).toDate()).toEqual(LATER);
  });

  it('已結帳、已作廢、已併走的單都擋下來，而且訊息各自講清楚', async () => {
    for (const [status, hint] of [
      ['closed', '已經結帳'],
      ['voided', '已經作廢'],
      ['merged', '併進別張單'],
    ] as const) {
      const { orderId } = await guestOrder();
      await db.doc(path.order(orderId)).update({ status });
      // 這一攤結束了，桌位的指標要跟著放掉，否則下一圈的 guestOrder() 會走到
      // 「本桌已結帳」那條路，測不到我們要測的東西。
      await db.doc(path.table('table_1')).update({ activeSessionId: null });

      await rejectsWith(
        confirmGuestOrder(db, { orderId, requestId: randomUUID() }, CLERK, LATER),
        'failed-precondition',
        hint,
      );
      expect((await orderDoc(orderId))['status']).toBe(status);
    }
  });

  it('找不到的單回 not-found', async () => {
    await rejectsWith(
      confirmGuestOrder(db, { orderId: 'order_nope', requestId: randomUUID() }, CLERK, LATER),
      'not-found',
    );
  });

  it('別家店的店員看不到這張單', async () => {
    // storeId 只從 token 來，所以別家店的 caller 走到的是另一條路徑下的同名文件，
    // 也就是不存在。這是多租戶隔離在 callable 這一層唯一的支點。
    const { orderId } = await guestOrder();

    await rejectsWith(
      confirmGuestOrder(
        db,
        { orderId, requestId: randomUUID() },
        { uid: 'uid_clerk_other', storeId: 'store_other', role: 'staff' },
        LATER,
      ),
      'not-found',
    );
    expect((await orderDoc(orderId))['status']).toBe('pending_confirm');
  });

  it('放行之後客人再加點，狀態維持 open，不會退回待確認', async () => {
    // SPEC 第十三節：已經確認過的單不因加點退回待確認（現在照 SPEC 不退回）。
    // 會退回的話，廚房已經在做的那碗會被當成還沒放行。
    const { orderId } = await guestOrder();
    await confirmGuestOrder(db, { orderId, requestId: randomUUID() }, CLERK, LATER);

    await createGuestOrder(
      db,
      {
        storeId: STORE,
        tableToken: TABLE_TOKEN,
        requestId: randomUUID(),
        items: [{ itemId: 'item_bubble_tea', qty: 1, options: [] }],
      },
      GUEST,
      LATER,
    );

    const order = await orderDoc(orderId);
    expect(order['status']).toBe('open');
    expect(order['total']).toBe(240);
  });
});
