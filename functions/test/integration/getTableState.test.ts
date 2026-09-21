import { randomUUID } from 'node:crypto';
import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import type { Firestore } from 'firebase-admin/firestore';
import { createGuestOrder } from '../../src/orders/createGuestOrder.js';
import { getTableState, TABLE_STATE_RATE_LIMIT } from '../../src/orders/getTableState.js';
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
const at = (sec: number) => new Date(NOW.getTime() + sec * 1000);

const GUEST = 'uid_guest_1';
const OTHER_GUEST = 'uid_guest_2';

beforeEach(async () => {
  await clearFirestore();
  await db.doc(path.publishedMenu).set(MENU);
  await db.doc(path.table('table_1')).set(TABLE);
});

afterAll(async () => {
  await disposeAdminDb();
});

const codeOf = (err: unknown) => (err as { code?: string }).code;

const state = (uid = GUEST, now = NOW) =>
  getTableState(db, { storeId: STORE, tableToken: TABLE_TOKEN }, uid, now);

/** 讓這張桌上有一張未結帳的單：牛肉麵 180 × qty。 */
async function orderNoodles(qty: number, uid = GUEST, now = NOW) {
  return createGuestOrder(
    db,
    {
      storeId: STORE,
      tableToken: TABLE_TOKEN,
      requestId: randomUUID(),
      items: [{ itemId: 'item_beef_noodle', qty, options: [] }],
    },
    uid,
    now,
  );
}

describe('桌號', () => {
  // 這支存在的理由之一：在它之前，桌號只有 createOrder 的回傳值帶得到，
  // 所以客人掃錯桌要等點完才發現。
  it('還沒點任何東西就拿得到桌號', async () => {
    const result = await state();
    expect(result.tableLabel).toBe(TABLE.label);
    expect(result.openOrder).toBeNull();
  });

  it('token 不對就是失效，不會洩漏這張桌存不存在', async () => {
    const wrong = getTableState(
      db,
      { storeId: STORE, tableToken: 'ffffffffffffffffffffffffffffffff' },
      GUEST,
      NOW,
    );
    await expect(wrong).rejects.toSatisfy((err: unknown) => {
      expect(codeOf(err)).toBe('permission-denied');
      expect(String((err as Error).message)).toContain('已經失效');
      return true;
    });
  });

  it('停用的桌位查不到', async () => {
    await db.doc(path.table('table_1')).update({ archived: true });
    await expect(state()).rejects.toSatisfy((err: unknown) => {
      expect(codeOf(err)).toBe('permission-denied');
      return true;
    });
  });
});

describe('這桌目前的單', () => {
  // 這支存在的理由之二：同桌併單是對的，但菜單頁沒先講，送出後才冒出前面的品項。
  it('有未結帳的單就回份數與金額', async () => {
    await orderNoodles(2);

    const result = await state(OTHER_GUEST, at(60));

    expect(result.openOrder).toEqual({ itemCount: 2, total: 360, status: 'pending_confirm' });
  });

  it('不回品項明細——掃到 QR code 的不保證是同桌的人', async () => {
    await orderNoodles(1);
    const result = await state(OTHER_GUEST, at(60));

    expect(JSON.stringify(result)).not.toContain('牛肉麵');
    expect(Object.keys(result.openOrder ?? {}).sort()).toEqual(['itemCount', 'status', 'total']);
  });

  it('加點之後份數與金額跟著變', async () => {
    await orderNoodles(1);
    await orderNoodles(2, GUEST, at(60));

    expect((await state(OTHER_GUEST, at(120))).openOrder).toMatchObject({
      itemCount: 3,
      total: 540,
    });
  });

  it('店員確認過（open）的單也算這一攤', async () => {
    const created = await orderNoodles(1);
    await db.doc(path.order(created.orderId)).update({ status: 'open' });

    expect((await state(OTHER_GUEST, at(60))).openOrder).toMatchObject({ status: 'open' });
  });

  it('作廢的行不算進份數', async () => {
    const created = await orderNoodles(2);
    const order = (await db.doc(path.order(created.orderId)).get()).data() ?? {};
    const lines = (order['lines'] as Record<string, unknown>[]) ?? [];
    await db
      .doc(path.order(created.orderId))
      .update({ lines: lines.map((l) => ({ ...l, voidedAt: new Date() })) });

    expect((await state(OTHER_GUEST, at(60))).openOrder?.itemCount).toBe(0);
  });
});

describe('這一攤結束之後', () => {
  // 結帳完那一攤就結束了，下一位客人掃進來是新的一攤，不該看到上一攤的金額。
  it('已結帳的單不算，桌號還是看得到', async () => {
    const created = await orderNoodles(1);
    await db.doc(path.order(created.orderId)).update({ status: 'closed' });

    const result = await state(OTHER_GUEST, at(60));
    expect(result.tableLabel).toBe(TABLE.label);
    expect(result.openOrder).toBeNull();
  });

  it('已作廢的單不算', async () => {
    const created = await orderNoodles(1);
    await db.doc(path.order(created.orderId)).update({ status: 'voided' });

    expect((await state(OTHER_GUEST, at(60))).openOrder).toBeNull();
  });

  // closeOrder 應該把桌上的 activeSessionId 清掉，這裡是防呆：萬一漏了，
  // 結果要是「開新的一攤」而不是讓這張桌從此顯示著上一攤的金額。
  it('session 結束了但指標沒清，也當成沒有單', async () => {
    const created = await orderNoodles(1);
    await db.doc(path.session(created.sessionId)).update({ status: 'closed' });

    expect((await state(OTHER_GUEST, at(60))).openOrder).toBeNull();
  });

  it('指標指到不存在的 session 也不會爆炸', async () => {
    await db.doc(path.table('table_1')).update({ activeSessionId: 'deadbeef'.repeat(4) });

    expect((await state()).openOrder).toBeNull();
  });
});

describe('限流與送出分開計數', () => {
  // 共用一個計數器的話，重新整理幾次就會把送出的額度吃光——點得了餐卻送不出去。
  it('讀到上限之後仍然送得出單', async () => {
    for (let i = 0; i < TABLE_STATE_RATE_LIMIT.max; i += 1) {
      await state(GUEST, at(i));
    }
    await expect(state(GUEST, at(TABLE_STATE_RATE_LIMIT.max))).rejects.toSatisfy(
      (err: unknown) => {
        expect(codeOf(err)).toBe('resource-exhausted');
        return true;
      },
    );

    // 送出那一支完全不受影響。
    const created = await orderNoodles(1, GUEST, at(TABLE_STATE_RATE_LIMIT.max + 1));
    expect(created.total).toBe(180);
  });

  it('過了時間窗就能再讀', async () => {
    for (let i = 0; i < TABLE_STATE_RATE_LIMIT.max; i += 1) {
      await state(GUEST, at(i));
    }
    const after = at(TABLE_STATE_RATE_LIMIT.windowSec + 5);
    expect((await state(GUEST, after)).tableLabel).toBe(TABLE.label);
  });

  it('一個客人讀爆不影響另一個客人', async () => {
    for (let i = 0; i < TABLE_STATE_RATE_LIMIT.max; i += 1) {
      await state(GUEST, at(i));
    }
    expect((await state(OTHER_GUEST, at(1))).tableLabel).toBe(TABLE.label);
  });
});
