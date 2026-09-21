import { randomUUID } from 'node:crypto';
import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import { Timestamp, type Firestore } from 'firebase-admin/firestore';
import type { StaffCaller } from '../../src/auth/staffAuth.js';
import { createGuestOrder } from '../../src/orders/createGuestOrder.js';
import { voidOrderLine } from '../../src/orders/voidOrderLine.js';
import type { VoidOrderLineInput } from '../../src/orders/voidOrderLineInput.js';
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

interface StoredLineDoc {
  lineId: string;
  name: string;
  subtotal: number;
  printedAt: Timestamp | null;
  voidedAt: Timestamp | null;
  voidReason?: string | null;
}

const linesOf = async (id: string) => ((await orderDoc(id))['lines'] ?? []) as StoredLineDoc[];

/**
 * 開一張有兩個品項的內用單：牛肉麵 180、珍珠奶茶 60，合計 240。
 * 顧客單一開始是 pending_confirm，退點在這個狀態下就該可以做（客人自己點錯了）。
 */
async function openOrder() {
  const created = await createGuestOrder(
    db,
    {
      storeId: STORE,
      tableToken: TABLE_TOKEN,
      requestId: randomUUID(),
      items: [
        { itemId: 'item_beef_noodle', qty: 1, options: [] },
        { itemId: 'item_bubble_tea', qty: 1, options: [] },
      ],
    },
    GUEST,
    NOW,
  );
  const lines = await linesOf(created.orderId);
  return {
    orderId: created.orderId,
    noodle: lines.find((l) => l.name === '牛肉麵')!,
    tea: lines.find((l) => l.name === '珍珠奶茶')!,
  };
}

/** 假裝這一行已經送進廚房了。真正寫 printedAt 的是列印佇列，那一段還沒做。 */
async function markPrinted(orderId: string, lineId: string) {
  const lines = await linesOf(orderId);
  await db.doc(path.order(orderId)).update({
    lines: lines.map((l) => (l.lineId === lineId ? { ...l, printedAt: Timestamp.fromDate(NOW) } : l)),
  });
}

const request = (over: Partial<VoidOrderLineInput> & { orderId: string; lineId: string }) => ({
  requestId: randomUUID(),
  ...over,
});

const void_ = (input: VoidOrderLineInput, caller = CLERK, now = LATER) =>
  voidOrderLine(db, input, caller, now);

describe('還沒送進廚房的行：整行移除', () => {
  it('那一行從訂單裡消失，總額重算', async () => {
    const { orderId, tea } = await openOrder();

    const result = await void_(request({ orderId, lineId: tea.lineId }));

    expect(result.outcome).toBe('removed');
    expect(result.total).toBe(180);

    const lines = await linesOf(orderId);
    expect(lines).toHaveLength(1);
    expect(lines[0]?.name).toBe('牛肉麵');

    const doc = await orderDoc(orderId);
    expect(doc['subtotal']).toBe(180);
    expect(doc['total']).toBe(180);
  });

  // 沒進廚房的東西不用通知廚房。多印一張作廢單只會讓廚師去找一張根本沒收到的單。
  it('不需要印作廢單', async () => {
    const { orderId, tea } = await openOrder();
    expect((await void_(request({ orderId, lineId: tea.lineId }))).needsVoidTicket).toBe(false);
  });

  it('退到剩最後一行，總額是 0', async () => {
    const { orderId, noodle, tea } = await openOrder();
    await void_(request({ orderId, lineId: tea.lineId }));
    const result = await void_(request({ orderId, lineId: noodle.lineId }));

    expect(result.total).toBe(0);
    expect(await linesOf(orderId)).toHaveLength(0);
    // 單本身留著：這張桌可能還要再點，作廢整張單是另一件事。
    expect((await orderDoc(orderId))['status']).toBe('pending_confirm');
  });

  it('留痕：誰退的、什麼時候退的', async () => {
    const { orderId, tea } = await openOrder();
    await void_(request({ orderId, lineId: tea.lineId }));

    const doc = await orderDoc(orderId);
    expect(doc['lastVoidedBy']).toBe(CLERK.uid);
    expect((doc['lastVoidedAt'] as Timestamp).toDate()).toEqual(LATER);
  });
});

describe('已經送進廚房的行：作廢留痕', () => {
  it('那一行留著，標上作廢時間與原因', async () => {
    const { orderId, tea } = await openOrder();
    await markPrinted(orderId, tea.lineId);

    const result = await void_(request({ orderId, lineId: tea.lineId, reason: '上錯桌' }));

    expect(result.outcome).toBe('voided');

    const lines = await linesOf(orderId);
    expect(lines).toHaveLength(2);
    const voided = lines.find((l) => l.lineId === tea.lineId)!;
    expect(voided.voidedAt?.toDate()).toEqual(LATER);
    expect(voided.voidReason).toBe('上錯桌');
  });

  it('作廢的行不計入總額', async () => {
    const { orderId, tea } = await openOrder();
    await markPrinted(orderId, tea.lineId);

    const result = await void_(request({ orderId, lineId: tea.lineId }));

    expect(result.total).toBe(180);
    expect((await orderDoc(orderId))['total']).toBe(180);
    // 行還在，但它自己的小計歸零（pricing.ts 的 lineSubtotal）。
    const voided = (await linesOf(orderId)).find((l) => l.lineId === tea.lineId)!;
    expect(voided.subtotal).toBe(0);
  });

  it('沒填原因就是 null，不是 undefined', async () => {
    const { orderId, tea } = await openOrder();
    await markPrinted(orderId, tea.lineId);
    await void_(request({ orderId, lineId: tea.lineId }));

    const voided = (await linesOf(orderId)).find((l) => l.lineId === tea.lineId)!;
    expect(voided.voidReason).toBeNull();
  });

  it('要印一張作廢單給廚房', async () => {
    const { orderId, tea } = await openOrder();
    await markPrinted(orderId, tea.lineId);
    expect((await void_(request({ orderId, lineId: tea.lineId }))).needsVoidTicket).toBe(true);
  });

  it('沒被退的那一行不受影響', async () => {
    const { orderId, noodle, tea } = await openOrder();
    await markPrinted(orderId, tea.lineId);
    await void_(request({ orderId, lineId: tea.lineId }));

    const other = (await linesOf(orderId)).find((l) => l.lineId === noodle.lineId)!;
    expect(other.voidedAt).toBeNull();
    expect(other.subtotal).toBe(180);
  });
});

describe('重送與重複退', () => {
  it('同一個 requestId 再送一次，什麼都不會再退', async () => {
    const { orderId, noodle, tea } = await openOrder();
    const input = request({ orderId, lineId: tea.lineId });

    await void_(input);
    const again = await void_(input);

    expect(again.outcome).toBe('already_applied');
    expect(again.total).toBe(180);
    // 第二次不可以順手把另一行也退掉，也不可以退回錯誤。
    const lines = await linesOf(orderId);
    expect(lines).toHaveLength(1);
    expect(lines[0]?.lineId).toBe(noodle.lineId);
  });

  it('重送不會再印一張作廢單', async () => {
    const { orderId, tea } = await openOrder();
    await markPrinted(orderId, tea.lineId);
    const input = request({ orderId, lineId: tea.lineId });

    expect((await void_(input)).needsVoidTicket).toBe(true);
    expect((await void_(input)).needsVoidTicket).toBe(false);
  });

  it('別人先退過同一行，後到的不會看到錯誤', async () => {
    const { orderId, tea } = await openOrder();
    await markPrinted(orderId, tea.lineId);

    await void_(request({ orderId, lineId: tea.lineId, reason: '客人不要了' }));
    const second = await void_(request({ orderId, lineId: tea.lineId, reason: '重複' }));

    expect(second.outcome).toBe('already_voided');
    expect(second.needsVoidTicket).toBe(false);
    // 第一個人寫下的原因不會被後到的覆蓋掉。
    const voided = (await linesOf(orderId)).find((l) => l.lineId === tea.lineId)!;
    expect(voided.voidReason).toBe('客人不要了');
  });
});

describe('擋下來的情況', () => {
  it('找不到單', async () => {
    await rejectsWith(
      void_(request({ orderId: 'order_nope', lineId: 'line_nope' })),
      'not-found',
      '找不到這張單',
    );
  });

  it('找不到那一行', async () => {
    const { orderId } = await openOrder();
    await rejectsWith(
      void_(request({ orderId, lineId: randomUUID() })),
      'not-found',
      '找不到這一筆',
    );
  });

  it('已經結帳的單退不了，要走作廢重開', async () => {
    const { orderId, tea } = await openOrder();
    await db.doc(path.order(orderId)).update({ status: 'closed' });

    await rejectsWith(
      void_(request({ orderId, lineId: tea.lineId })),
      'failed-precondition',
      '作廢重開',
    );
  });

  it('已經作廢的單退不了', async () => {
    const { orderId, tea } = await openOrder();
    await db.doc(path.order(orderId)).update({ status: 'voided' });

    await rejectsWith(void_(request({ orderId, lineId: tea.lineId })), 'failed-precondition');
  });

  /**
   * 多租戶隔離：店在哪一間只由呼叫者的 claims 決定。
   *
   * 別家店的店員拿到這張單的 orderId（訂單 id 不是秘密，出單上就印得到），
   * 打進來時找的是 tenants/store_other/orders/... 那條路徑，根本不存在。
   */
  it('別家店的店員動不了這張單', async () => {
    const { orderId, tea } = await openOrder();
    const other: StaffCaller = { uid: 'uid_other_1', storeId: 'store_other', role: 'owner' };

    await rejectsWith(void_(request({ orderId, lineId: tea.lineId }), other), 'not-found');
    // 原單一行都沒少。
    expect(await linesOf(orderId)).toHaveLength(2);
  });
});

describe('與加點的互動', () => {
  /**
   * 回歸測試。加點是「整個 lines 讀出來、加新的、整個寫回去」，只要來回時掉了 printedAt，
   * 已經在做的菜就會被退點當成點錯的直接刪掉。
   */
  it('加點之後，已列印的標記還在，退點仍然只作廢不刪除', async () => {
    const { orderId, tea } = await openOrder();
    await markPrinted(orderId, tea.lineId);

    await createGuestOrder(
      db,
      {
        storeId: STORE,
        tableToken: TABLE_TOKEN,
        requestId: randomUUID(),
        items: [{ itemId: 'item_rice', qty: 1, options: [] }],
      },
      GUEST,
      LATER,
    );

    expect((await linesOf(orderId)).find((l) => l.lineId === tea.lineId)?.printedAt).not.toBeNull();

    const result = await void_(request({ orderId, lineId: tea.lineId }));
    expect(result.outcome).toBe('voided');
    expect((await linesOf(orderId))).toHaveLength(3);
  });
});
