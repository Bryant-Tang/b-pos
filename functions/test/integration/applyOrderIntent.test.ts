import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import { applyOrderIntent } from '../../src/orders/applyOrderIntent.js';
import {
  MENU,
  STORE,
  TABLE,
  adminDb,
  clearFirestore,
  disposeAdminDb,
  path,
} from './helpers.js';

const db: Firestore = adminDb();

// 營業日 2026-09-20（台北晚上七點）
const NOW = new Date('2026-09-20T19:00:00+08:00');
const BUSINESS_DATE = '2026-09-20';

beforeEach(async () => {
  await clearFirestore();
  await db.doc(path.publishedMenu).set(MENU);
  await db.doc(path.table('table_1')).set(TABLE);
});

afterAll(async () => {
  await disposeAdminDb();
});

type IntentOverrides = Record<string, unknown>;

function intentData(over: IntentOverrides = {}) {
  return {
    intentId: 'intent_1',
    orderId: 'order_1',
    orderType: 'dine_in',
    tableId: 'table_1',
    lines: [{ itemId: 'item_beef_noodle', qty: 1, options: [] }],
    createdBy: 'uid_staff',
    clientCreatedAt: Timestamp.fromDate(NOW),
    ...over,
  };
}

/** 把意圖寫進 Firestore（模擬店員平板的 set()），再跑觸發器會跑的那段。 */
async function submit(over: IntentOverrides = {}, at: Date = NOW) {
  const data = intentData(over);
  const intentId = String(data['intentId']);
  await db.doc(path.orderIntent(intentId)).set(data);
  return applyOrderIntent(db, STORE, intentId, data, at);
}

const orderData = async (id = 'order_1') => (await db.doc(path.order(id)).get()).data() ?? {};
const intentDoc = async (id = 'intent_1') => (await db.doc(path.orderIntent(id)).get()).data() ?? {};

describe('建立新單', () => {
  it('內用單：價格由伺服器查出來算，不是客戶端送的', async () => {
    const outcome = await submit();
    expect(outcome).toEqual({ status: 'created', orderId: 'order_1', pickupCode: null });

    const order = await orderData();
    expect(order['subtotal']).toBe(180);
    expect(order['total']).toBe(180);
    expect(order['serviceCharge']).toBe(0);
    expect(order['status']).toBe('open');
    expect(order['source']).toBe('staff');
    expect(order['orderType']).toBe('dine_in');
    expect(order['tableIds']).toEqual(['table_1']);
    expect(order['tableLabels']).toEqual(['窗邊']);
    expect(order['createdBy']).toBe('uid_staff');
    expect(order['appliedIntentIds']).toEqual(['intent_1']);
    expect(order['menuVersion']).toBe(MENU.version);
  });

  it('價格快照存雙價，轉型態時不必回頭查菜單', async () => {
    await submit({ lines: [{ itemId: 'item_bubble_tea', qty: 2, options: [] }] });
    const [line] = (await orderData())['lines'] as Record<string, unknown>[];
    expect(line?.['name']).toBe('珍珠奶茶');
    expect(line?.['unitPriceDineIn']).toBe(60);
    expect(line?.['unitPriceTakeout']).toBe(55);
    expect(line?.['subtotal']).toBe(120);
  });

  it('外帶用外帶價，並拿到 4 碼取餐號', async () => {
    const outcome = await submit({
      orderType: 'takeout',
      tableId: null,
      lines: [{ itemId: 'item_bubble_tea', qty: 2, options: [] }],
    });
    expect(outcome).toEqual({ status: 'created', orderId: 'order_1', pickupCode: '0001' });

    const order = await orderData();
    expect(order['subtotal']).toBe(110); // 55 × 2
    expect(order['tableIds']).toEqual([]);
    expect(order['tableLabels']).toEqual([]);
    expect(order['pickupCode']).toBe('0001');
  });

  it('外帶與候位共用同一套發號，內用不佔號', async () => {
    await submit({ intentId: 'i1', orderId: 'o1', orderType: 'takeout', tableId: null });
    await submit({ intentId: 'i2', orderId: 'o2', orderType: 'dine_in', tableId: 'table_1' });
    await submit({ intentId: 'i3', orderId: 'o3', orderType: 'waitlist', tableId: null });

    expect((await orderData('o1'))['pickupCode']).toBe('0001');
    expect((await orderData('o2'))['pickupCode']).toBeNull();
    expect((await orderData('o3'))['pickupCode']).toBe('0002');

    const counter = (await db.doc(path.counter(BUSINESS_DATE)).get()).data();
    expect(counter?.['nextPickupCode']).toBe(3);
  });

  it('發號按營業日歸零，不是按日曆日', async () => {
    await submit({ intentId: 'i1', orderId: 'o1', orderType: 'takeout', tableId: null });

    // 同一個營業日的凌晨兩點（換日時間五點），號碼要接下去而不是重來。
    await submit(
      { intentId: 'i2', orderId: 'o2', orderType: 'takeout', tableId: null },
      new Date('2026-09-21T02:00:00+08:00'),
    );
    expect((await orderData('o2'))['pickupCode']).toBe('0002');

    // 台北 21 日早上七點在 UTC 還是 20 日，所以這一筆能分辨「營業日」與「日曆日」：
    // 照營業日算要從 0001 重來，照 UTC 日曆日算會錯成 0003。
    await submit(
      { intentId: 'i3', orderId: 'o3', orderType: 'takeout', tableId: null },
      new Date('2026-09-21T07:00:00+08:00'),
    );
    expect((await orderData('o3'))['pickupCode']).toBe('0001');

    expect((await db.doc(path.counter('2026-09-20')).get()).data()?.['nextPickupCode']).toBe(3);
    expect((await db.doc(path.counter('2026-09-21')).get()).data()?.['nextPickupCode']).toBe(2);
  });

  it('意圖文件上會記下套用時間與訂單', async () => {
    await submit();
    const intent = await intentDoc();
    expect(intent['orderId']).toBe('order_1');
    expect(intent['appliedAt']).toBeInstanceOf(Timestamp);
    expect(intent['rejectReason']).toBeUndefined();
  });

  it('businessDate 留空，由 closeOrder 在結帳當下寫', async () => {
    await submit();
    expect((await orderData())['businessDate']).toBeNull();
  });
});

describe('冪等：同一份意圖送兩次只算一次', () => {
  it('第二次回 already_applied，訂單不動', async () => {
    const first = await submit();
    expect(first.status).toBe('created');

    const before = await orderData();
    const second = await applyOrderIntent(db, STORE, 'intent_1', intentData(), NOW);
    expect(second).toEqual({ status: 'already_applied', orderId: 'order_1' });

    const after = await orderData();
    expect(after['total']).toBe(before['total']);
    expect((after['lines'] as unknown[]).length).toBe((before['lines'] as unknown[]).length);
    expect(after['appliedIntentIds']).toEqual(['intent_1']);
  });

  it('連送五次的結果跟送一次一樣', async () => {
    for (let i = 0; i < 5; i += 1) {
      await applyOrderIntent(db, STORE, 'intent_1', intentData(), NOW);
    }
    const order = await orderData();
    expect(order['total']).toBe(180);
    expect((order['lines'] as unknown[]).length).toBe(1);
  });
});

describe('加點：同一張單、不同意圖', () => {
  it('附加品項並重算總額', async () => {
    await submit();
    const outcome = await submit({
      intentId: 'intent_2',
      lines: [{ itemId: 'item_bubble_tea', qty: 1, options: [] }],
    });
    expect(outcome).toEqual({ status: 'appended', orderId: 'order_1' });

    const order = await orderData();
    expect((order['lines'] as unknown[]).length).toBe(2);
    expect(order['subtotal']).toBe(240); // 180 + 60
    expect(order['total']).toBe(240);
    expect(order['appliedIntentIds']).toEqual(['intent_1', 'intent_2']);
  });

  it('每一筆 line 的 lineId 都不重複', async () => {
    await submit();
    await submit({ intentId: 'intent_2' });
    await submit({ intentId: 'intent_3' });
    const lines = (await orderData())['lines'] as { lineId: string }[];
    expect(lines).toHaveLength(3);
    expect(new Set(lines.map((l) => l.lineId)).size).toBe(3);
  });

  it('加點不會把已套用的折扣弄不見', async () => {
    await submit();
    await db.doc(path.order('order_1')).update({ discount: 50, total: 130 });
    await submit({ intentId: 'intent_2', lines: [{ itemId: 'item_bubble_tea', qty: 1, options: [] }] });

    const order = await orderData();
    expect(order['discount']).toBe(50);
    expect(order['subtotal']).toBe(240);
    expect(order['total']).toBe(190); // 240 − 50
  });

  it('內用服務費在加點後重算', async () => {
    await db.doc(path.pricingSettings).set({ dineInServiceCharge: 0.1 });
    await submit();
    let order = await orderData();
    expect(order['serviceCharge']).toBe(18);
    expect(order['total']).toBe(198);

    await submit({ intentId: 'intent_2', lines: [{ itemId: 'item_bubble_tea', qty: 1, options: [] }] });
    order = await orderData();
    expect(order['subtotal']).toBe(240);
    expect(order['serviceCharge']).toBe(24);
    expect(order['total']).toBe(264);
  });

  it('外帶不收服務費', async () => {
    await db.doc(path.pricingSettings).set({ dineInServiceCharge: 0.1 });
    await submit({ orderType: 'takeout', tableId: null });
    expect((await orderData())['serviceCharge']).toBe(0);
  });
});

describe('拒絕：理由要寫回意圖文件，平板才看得到', () => {
  const rejectedWith = async (reason: string, intentId = 'intent_1') => {
    const intent = await intentDoc(intentId);
    expect(intent['rejectReason']).toBe(reason);
    expect(intent['rejectedAt']).toBeInstanceOf(Timestamp);
    expect(intent['appliedAt']).toBeUndefined();
  };

  it('偷塞金額欄位', async () => {
    const outcome = await submit({ total: 0 });
    expect(outcome.status).toBe('rejected');
    expect((outcome as { reason: string }).reason).toBe('invalid_intent');
    await rejectedWith('invalid_intent');
    expect((await db.doc(path.order('order_1')).get()).exists).toBe(false);
  });

  it('內用沒帶桌號', async () => {
    const outcome = await submit({ tableId: null });
    expect((outcome as { reason: string }).reason).toBe('invalid_intent');
    await rejectedWith('invalid_intent');
  });

  it('品項不存在', async () => {
    const outcome = await submit({ lines: [{ itemId: 'item_ghost', qty: 1, options: [] }] });
    expect((outcome as { reason: string }).reason).toBe('pricing_error');
    await rejectedWith('pricing_error');
  });

  it('同一個負數選項送十次（壓低金額）', async () => {
    const options = Array.from({ length: 10 }, () => ({
      groupId: 'grp_remove',
      optionId: 'opt_no_meat',
    }));
    const outcome = await submit({ lines: [{ itemId: 'item_rice', qty: 1, options }] });
    expect((outcome as { reason: string }).reason).toBe('pricing_error');
    await rejectedWith('pricing_error');
  });

  it('套用沒掛在該品項上的選項群組', async () => {
    const outcome = await submit({
      lines: [
        { itemId: 'item_bubble_tea', qty: 1, options: [{ groupId: 'grp_remove', optionId: 'opt_no_meat' }] },
      ],
    });
    expect((outcome as { reason: string }).reason).toBe('pricing_error');
  });

  it('兩個不同的負數選項疊起來，單行小計夾在 0 而不是拒絕', async () => {
    // 這是菜單設定問題不是攻擊，營業中不該因此點不了餐（見 pricing.ts 的說明）。
    const outcome = await submit({
      lines: [
        {
          itemId: 'item_rice',
          qty: 1,
          options: [
            { groupId: 'grp_remove', optionId: 'opt_no_meat' },
            { groupId: 'grp_remove', optionId: 'opt_no_egg' },
          ],
        },
      ],
    });
    expect(outcome.status).toBe('created');
    expect((await orderData())['total']).toBe(0);
  });

  it('菜單還沒發佈', async () => {
    await db.doc(path.publishedMenu).delete();
    const outcome = await submit();
    expect((outcome as { reason: string }).reason).toBe('menu_not_published');
    await rejectedWith('menu_not_published');
  });

  it('桌位不存在', async () => {
    const outcome = await submit({ tableId: 'table_ghost' });
    expect((outcome as { reason: string }).reason).toBe('unknown_table');
    await rejectedWith('unknown_table');
  });

  it('intentId 與文件 id 不一致', async () => {
    await db.doc(path.orderIntent('intent_1')).set(intentData());
    const outcome = await applyOrderIntent(
      db,
      STORE,
      'intent_1',
      intentData({ intentId: 'intent_other' }),
      NOW,
    );
    expect((outcome as { reason: string }).reason).toBe('invalid_intent');
  });

  it('加點到已結帳的單', async () => {
    await submit();
    await db.doc(path.order('order_1')).update({ status: 'closed' });
    const outcome = await submit({ intentId: 'intent_2' });
    expect((outcome as { reason: string }).reason).toBe('order_closed');
    await rejectedWith('order_closed', 'intent_2');
    expect((await orderData())['subtotal']).toBe(180);
  });

  it('加點到型態不同的單', async () => {
    await submit();
    const outcome = await submit({ intentId: 'intent_2', orderType: 'takeout', tableId: null });
    expect((outcome as { reason: string }).reason).toBe('order_type_mismatch');
  });

  it('加點時帶了不屬於這張單的桌號', async () => {
    await submit();
    await db.doc(path.table('table_2')).set({ ...TABLE, label: '吧台3' });
    const outcome = await submit({ intentId: 'intent_2', tableId: 'table_2' });
    expect((outcome as { reason: string }).reason).toBe('table_mismatch');
  });
});

describe('設定壞掉時不要讓整間店點不了餐', () => {
  it('服務費設定不是合法比例就當作 0（少收不是多收）', async () => {
    await db.doc(path.pricingSettings).set({ dineInServiceCharge: 'ten percent' });
    const outcome = await submit();
    expect(outcome.status).toBe('created');
    const order = await orderData();
    expect(order['serviceCharge']).toBe(0);
    expect(order['total']).toBe(180);
  });

  it('換日時間設定不合法就退回預設的凌晨五點', async () => {
    await db.doc(path.businessSettings).set({ dayCloseHour: 99 });
    await submit({ orderType: 'takeout', tableId: null }, new Date('2026-09-21T02:00:00+08:00'));
    // 退回 5 點的話，凌晨兩點屬於 09-20 這個營業日。
    const counter = (await db.doc(path.counter(BUSINESS_DATE)).get()).data();
    expect(counter?.['nextPickupCode']).toBe(2);
  });

  it('換日時間設得出來就要照著用', async () => {
    await db.doc(path.businessSettings).set({ dayCloseHour: 0, timeZone: 'Asia/Taipei' });
    await submit({ orderType: 'takeout', tableId: null }, new Date('2026-09-21T02:00:00+08:00'));
    const counter = (await db.doc(path.counter('2026-09-21')).get()).data();
    expect(counter?.['nextPickupCode']).toBe(2);
  });
});
