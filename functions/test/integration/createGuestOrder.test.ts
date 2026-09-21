import { randomUUID } from 'node:crypto';
import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import { createGuestOrder } from '../../src/orders/createGuestOrder.js';
import type { CreateOrderInput } from '../../src/orders/createOrderInput.js';
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

// 營業日 2026-09-20（台北晚上七點）
const NOW = new Date('2026-09-20T19:00:00+08:00');
const at = (sec: number) => new Date(NOW.getTime() + sec * 1000);

const GUEST = 'uid_guest_1';

beforeEach(async () => {
  await clearFirestore();
  await db.doc(path.publishedMenu).set(MENU);
  await db.doc(path.table('table_1')).set(TABLE);
});

afterAll(async () => {
  await disposeAdminDb();
});

function order(over: Partial<CreateOrderInput> = {}): CreateOrderInput {
  return {
    storeId: STORE,
    tableToken: TABLE_TOKEN,
    // 預設每次都是新的一次送出；要測重複送出就把 requestId 指定成同一個。
    requestId: randomUUID(),
    items: [{ itemId: 'item_beef_noodle', qty: 1, options: [] }],
    ...over,
  };
}

/** 顧客按下送出。時間可以指定，限流的視窗才測得出來。 */
const submit = (over: Partial<CreateOrderInput> = {}, uid = GUEST, now = NOW) =>
  createGuestOrder(db, order(over), uid, now);

const orderDoc = async (id: string) => (await db.doc(path.order(id)).get()).data() ?? {};
const sessionDoc = async (id: string) => (await db.doc(path.session(id)).get()).data() ?? {};
const tableDoc = async () => (await db.doc(path.table('table_1')).get()).data() ?? {};

/** HttpsError 的 code，例如 'permission-denied'。 */
const codeOf = (err: unknown) => (err as { code?: string }).code;

async function rejectsWith(promise: Promise<unknown>, code: string, message?: string) {
  await expect(promise).rejects.toSatisfy((err: unknown) => {
    expect(codeOf(err)).toBe(code);
    if (message !== undefined) expect(String((err as Error).message)).toContain(message);
    return true;
  });
}

describe('第一次送出：自動開桌', () => {
  it('建立 pending_confirm 的顧客單，金額由伺服器算', async () => {
    const result = await submit();

    expect(result.total).toBe(180);
    expect(result.tableLabel).toBe('窗邊');
    expect(result.status).toBe('pending_confirm');

    const created = await orderDoc(result.orderId);
    expect(created['appliedRequestIds']).toHaveLength(1);
    // 顧客單一定停在待確認，不直接進廚房（SPEC 第五節）。
    expect(created['status']).toBe('pending_confirm');
    expect(created['source']).toBe('guest');
    expect(created['orderType']).toBe('dine_in');
    expect(created['subtotal']).toBe(180);
    expect(created['total']).toBe(180);
    expect(created['serviceCharge']).toBe(0);
    expect(created['tableIds']).toEqual(['table_1']);
    expect(created['tableLabels']).toEqual(['窗邊']);
    expect(created['sessionIds']).toEqual([result.sessionId]);
    expect(created['createdBy']).toBe(GUEST);
    expect(created['businessDate']).toBeNull();
    expect(created['menuVersion']).toBe(MENU.version);
  });

  it('開出一個 active session，桌位文件指向它', async () => {
    const result = await submit();

    // sessionId 是能力憑證，必須猜不到（SPEC 第十三節〈三個憑證〉）。
    expect(result.sessionId).toMatch(/^[0-9a-f]{32}$/);

    const session = await sessionDoc(result.sessionId);
    expect(session['status']).toBe('active');
    expect(session['tableId']).toBe('table_1');
    expect(session['orderId']).toBe(result.orderId);
    expect(session['closedAt']).toBeNull();
    expect(session['readableUntil']).toBeNull();

    expect((await tableDoc())['activeSessionId']).toBe(result.sessionId);
  });

  it('選項加價算進小計', async () => {
    const result = await submit({
      items: [
        {
          itemId: 'item_beef_noodle',
          qty: 2,
          options: [{ groupId: 'grp_spicy', optionId: 'opt_extra' }],
        },
      ],
    });
    // （180 + 10）× 2
    expect(result.total).toBe(380);
    expect(result.lines[0]?.options).toEqual([{ name: '加辣加價', priceDelta: 10 }]);
  });

  it('內用價與外帶價不同時，顧客掃碼點的是內用價', async () => {
    const result = await submit({ items: [{ itemId: 'item_bubble_tea', qty: 1, options: [] }] });
    expect(result.total).toBe(60);
  });

  it('服務費照店家設定計算', async () => {
    await db.doc(path.pricingSettings).set({ dineInServiceCharge: 0.1 });
    const result = await submit();
    expect(result.serviceCharge).toBe(18);
    expect(result.total).toBe(198);
  });
});

describe('同一張桌再送一次：加點到同一張單', () => {
  it('附加到既有的單，不會開第二張', async () => {
    const first = await submit();
    const second = await submit({ items: [{ itemId: 'item_rice', qty: 1, options: [] }] }, GUEST, at(30));

    expect(second.orderId).toBe(first.orderId);
    expect(second.sessionId).toBe(first.sessionId);
    expect(second.total).toBe(210);
    expect(second.lines).toHaveLength(2);

    const orders = await db.collection(`tenants/${STORE}/orders`).get();
    expect(orders.size).toBe(1);
  });

  it('店員確認過（open）之後加點，狀態維持 open', async () => {
    const first = await submit();
    await db.doc(path.order(first.orderId)).update({ status: 'open' });

    const second = await submit({}, GUEST, at(30));
    expect(second.status).toBe('open');
    expect((await orderDoc(first.orderId))['status']).toBe('open');
  });

  it('每一行有自己的 lineId，加點不會撞號', async () => {
    const first = await submit();
    const second = await submit({}, GUEST, at(30));
    const ids = second.lines.map((l) => l.lineId);
    expect(new Set(ids).size).toBe(2);
    expect(ids).toContain(first.lines[0]?.lineId);
  });

  it('已結帳的單不能再加點，訊息要講怎麼辦', async () => {
    const first = await submit();
    await db.doc(path.order(first.orderId)).update({ status: 'closed' });

    await rejectsWith(submit({}, GUEST, at(30)), 'failed-precondition', '請洽服務人員');
  });

  it('上一組客人已結帳（session closed）時，新客人開新的一張單', async () => {
    const first = await submit();
    // closeOrder 會把 session 關掉並清掉桌位指標；這裡只關 session，
    // 驗證指標沒清乾淨時也不會讓這張桌從此點不了餐。
    await db.doc(path.session(first.sessionId)).update({ status: 'closed' });

    const second = await submit({}, 'uid_guest_2', at(60));
    expect(second.orderId).not.toBe(first.orderId);
    expect(second.sessionId).not.toBe(first.sessionId);
    expect(second.status).toBe('pending_confirm');
    expect((await tableDoc())['activeSessionId']).toBe(second.sessionId);
  });
});

describe('同一次送出重複打進來', () => {
  // 網路慢的時候客人會連按兩下，前端收不到回應也會重試。兩者送的是同一張單，
  // 不是再點一份（CLAUDE.md 第二節第三條：重試必須冪等）。
  it('同一個 requestId 送兩次，品項只算一次', async () => {
    const requestId = randomUUID();
    const first = await submit({ requestId });
    const second = await submit({ requestId }, GUEST, at(2));

    expect(second.orderId).toBe(first.orderId);
    expect(second.sessionId).toBe(first.sessionId);
    expect(second.lines).toHaveLength(1);
    expect(second.total).toBe(180);
    expect((await orderDoc(first.orderId))['total']).toBe(180);
  });

  it('連按兩下（兩次呼叫同時進來）也只算一次', async () => {
    const requestId = randomUUID();
    const results = await Promise.all([submit({ requestId }), submit({ requestId })]);

    expect(results[0].orderId).toBe(results[1].orderId);
    const created = await orderDoc(results[0].orderId);
    expect(created['lines']).toHaveLength(1);
    expect(created['total']).toBe(180);
  });

  it('換一個 requestId 就是真的要加點', async () => {
    const first = await submit();
    const second = await submit({}, GUEST, at(2));

    expect(second.orderId).toBe(first.orderId);
    expect(second.lines).toHaveLength(2);
    expect(second.total).toBe(360);
  });

  it('單子已經結帳後，重播同一次送出仍回得到那張單', async () => {
    const requestId = randomUUID();
    const first = await submit({ requestId });
    await db.doc(path.order(first.orderId)).update({ status: 'closed' });

    // 重播不是加點，不該回「本桌已結帳」——那次送出早就成功了。
    const replay = await submit({ requestId }, GUEST, at(2));
    expect(replay.orderId).toBe(first.orderId);
    expect(replay.status).toBe('closed');
    expect(replay.total).toBe(180);
  });
});

describe('兩位客人同時按送出', () => {
  // SPEC 第五節整段放在 transaction 裡的唯一理由。桌位文件是那把鎖：
  // 只查「有沒有 active session」擋不住，兩邊都會查到空的。
  it('只會開出一個 session 與一張單', async () => {
    const results = await Promise.all([
      submit({}, 'uid_guest_a'),
      submit({ items: [{ itemId: 'item_rice', qty: 1, options: [] }] }, 'uid_guest_b'),
    ]);

    expect(results[0].orderId).toBe(results[1].orderId);
    expect(results[0].sessionId).toBe(results[1].sessionId);

    const orders = await db.collection(`tenants/${STORE}/orders`).get();
    const sessions = await db.collection(`tenants/${STORE}/sessions`).get();
    expect(orders.size).toBe(1);
    expect(sessions.size).toBe(1);

    // 兩份品項都在，沒有人的餐被蓋掉：180 + 30
    expect((await orderDoc(results[0].orderId))['total']).toBe(210);
  });
});

describe('擋下來的情況', () => {
  it('token 對不到桌位', async () => {
    await rejectsWith(submit({ tableToken: 'f'.repeat(32) }), 'permission-denied');
  });

  it('桌位已封存', async () => {
    await db.doc(path.table('table_1')).update({ archived: true });
    await rejectsWith(submit(), 'permission-denied');
  });

  it('別間店的 storeId 配這張桌的 token', async () => {
    await rejectsWith(submit({ storeId: 'store_other' }), 'permission-denied');
  });

  it('菜單還沒發佈', async () => {
    await db.doc(path.publishedMenu).delete();
    await rejectsWith(submit(), 'failed-precondition', '還沒有發佈菜單');
  });

  it('點到菜單上沒有的品項，請客人重新整理', async () => {
    await rejectsWith(
      submit({ items: [{ itemId: 'item_not_on_menu', qty: 1, options: [] }] }),
      'failed-precondition',
      '菜單剛剛更新了',
    );
  });

  it('選項不屬於這個品項', async () => {
    await rejectsWith(
      submit({
        items: [
          {
            itemId: 'item_beef_noodle',
            qty: 1,
            options: [{ groupId: 'grp_remove', optionId: 'opt_no_meat' }],
          },
        ],
      }),
      'failed-precondition',
    );
  });

  it('被擋下時不會留下半張單', async () => {
    await rejectsWith(submit({ tableToken: 'f'.repeat(32) }), 'permission-denied');
    expect((await db.collection(`tenants/${STORE}/orders`).get()).size).toBe(0);
    expect((await tableDoc())['activeSessionId']).toBeUndefined();
  });
});

describe('限流', () => {
  it('一分鐘內第六次被擋下', async () => {
    for (let i = 0; i < 5; i += 1) {
      await submit({}, GUEST, at(i));
    }
    await rejectsWith(submit({}, GUEST, at(5)), 'resource-exhausted', '秒後再試');
  });

  it('過了視窗就放行', async () => {
    for (let i = 0; i < 5; i += 1) {
      await submit({}, GUEST, at(i));
    }
    await expect(submit({}, GUEST, at(60))).resolves.toBeDefined();
  });

  it('計數是按 uid 分開的', async () => {
    for (let i = 0; i < 5; i += 1) {
      await submit({}, GUEST, at(i));
    }
    await expect(submit({}, 'uid_guest_other', at(5))).resolves.toBeDefined();
  });

  it('計數文件記在這間店底下', async () => {
    await submit();
    const doc = (await db.doc(path.rateLimit(GUEST)).get()).data() ?? {};
    expect(doc['count']).toBe(1);
    expect(doc['windowStart']).toEqual(Timestamp.fromDate(NOW));
  });
});
