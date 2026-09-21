import { describe, expect, it } from 'vitest';
import { cartFingerprint, prunedPending, reusableRequestId, sessionEnded } from '../src/session.js';
import { addLine, type CartLine } from '../src/cart.js';
import type { GuestOrder, TableState } from '../src/api.js';
import { item, pick } from './fixtures.js';

const mild = pick('grp_spicy', 'opt_mild');
const extra = pick('grp_spicy', 'opt_extra');

const STORE = 'store_demo';
const TOKEN = '0123456789abcdef0123456789abcdef';
const R1 = '11111111-2222-4333-8444-555555555555';

const cartOf = (...adds: [ReturnType<typeof item>, typeof mild[], number][]) =>
  adds.reduce<CartLine[]>((lines, [it, options, qty]) => addLine(lines, it, options, qty), []);

const pending = (lines: CartLine[], over: Record<string, unknown> = {}) => ({
  storeId: STORE,
  tableToken: TOKEN,
  requestId: R1,
  fingerprint: cartFingerprint(lines),
  lines,
  ...over,
});

describe('cartFingerprint', () => {
  it('加入順序不同但內容一樣，指紋一樣', () => {
    const a = cartOf([item('item_beef_noodle'), [mild], 1], [item('item_rice'), [], 2]);
    const b = cartOf([item('item_rice'), [], 2], [item('item_beef_noodle'), [mild], 1]);
    expect(cartFingerprint(a)).toBe(cartFingerprint(b));
  });

  it('數量不同就是不同', () => {
    const a = cartOf([item('item_rice'), [], 1]);
    const b = cartOf([item('item_rice'), [], 2]);
    expect(cartFingerprint(a)).not.toBe(cartFingerprint(b));
  });

  it('品項不同就是不同', () => {
    const a = cartOf([item('item_rice'), [], 1]);
    const b = cartOf([item('item_bubble_tea'), [], 1]);
    expect(cartFingerprint(a)).not.toBe(cartFingerprint(b));
  });

  it('選項不同就是不同', () => {
    const a = cartOf([item('item_beef_noodle'), [mild], 1]);
    const b = cartOf([item('item_beef_noodle'), [extra], 1]);
    expect(cartFingerprint(a)).not.toBe(cartFingerprint(b));
  });
});

describe('reusableRequestId', () => {
  const cart = cartOf([item('item_beef_noodle'), [mild], 1]);

  it('同一張桌、同樣的內容就沿用——這才是「同一次送出」', () => {
    expect(reusableRequestId(pending(cart), STORE, TOKEN, cartFingerprint(cart))).toBe(R1);
  });

  it('沒有待確認的送出就不沿用', () => {
    expect(reusableRequestId(null, STORE, TOKEN, cartFingerprint(cart))).toBeNull();
  });

  /**
   * 這一項是整個機制的重點，退化了會變成靜默漏單：
   *
   * 送出成功但回應掉了 → 客人重新整理 → 點了別的東西再送出。沿用舊的 requestId
   * 的話，伺服器認出那個鍵用過了，原樣回傳舊訂單，這次點的東西從來沒進到伺服器，
   * 而且沒有任何錯誤訊息。漏單比重複下單嚴重得多。
   */
  it('內容不一樣就不沿用，否則新點的東西會被伺服器當成重送而丟掉', () => {
    const different = cartOf([item('item_bubble_tea'), [], 1]);
    expect(reusableRequestId(pending(cart), STORE, TOKEN, cartFingerprint(different))).toBeNull();
  });

  it('同樣的品項但數量變了也不沿用', () => {
    const more = cartOf([item('item_beef_noodle'), [mild], 2]);
    expect(reusableRequestId(pending(cart), STORE, TOKEN, cartFingerprint(more))).toBeNull();
  });

  it('換了一張桌就不沿用，不要把這桌的單送到別桌', () => {
    const otherToken = 'f'.repeat(32);
    expect(reusableRequestId(pending(cart), STORE, otherToken, cartFingerprint(cart))).toBeNull();
  });

  it('換了一間店也不沿用', () => {
    expect(reusableRequestId(pending(cart), 'store_other', TOKEN, cartFingerprint(cart))).toBeNull();
  });
});

describe('prunedPending', () => {
  const two = cartOf([item('item_beef_noodle'), [mild], 1], [item('item_bubble_tea'), [], 1]);
  const onlyNoodle = two.filter((line) => line.itemId === 'item_beef_noodle');

  it('拿掉一項之後沿用同一把 requestId', () => {
    const next = prunedPending(pending(two), STORE, TOKEN, onlyNoodle);
    expect(next?.requestId).toBe(R1);
  });

  it('指紋跟著改成修剪後的內容，否則送出時會被判成另一次', () => {
    const next = prunedPending(pending(two), STORE, TOKEN, onlyNoodle);
    expect(next?.fingerprint).toBe(cartFingerprint(onlyNoodle));
    expect(
      reusableRequestId(next, STORE, TOKEN, cartFingerprint(onlyNoodle)),
    ).toBe(R1);
  });

  // 這一條是整個函式存在的理由：加品項必須換新鍵，不能偷偷沿用舊的。
  it('多一項不算修剪，回傳 null', () => {
    const three = addLine(two, item('item_rice'), [], 1);
    expect(prunedPending(pending(two), STORE, TOKEN, three)).toBeNull();
  });

  it('份數變多也不算修剪', () => {
    const more = cartOf([item('item_beef_noodle'), [mild], 2]);
    expect(prunedPending(pending(onlyNoodle), STORE, TOKEN, more)).toBeNull();
  });

  it('換成完全不同的品項不算修剪', () => {
    const other = cartOf([item('item_rice'), [], 1]);
    expect(prunedPending(pending(two), STORE, TOKEN, other)).toBeNull();
  });

  it('修剪到空的就不要留著那把鍵', () => {
    expect(prunedPending(pending(two), STORE, TOKEN, [])).toBeNull();
  });

  it('別桌的記錄不動它', () => {
    const elsewhere = '89abcdef89abcdef89abcdef89abcdef';
    expect(prunedPending(pending(two), STORE, elsewhere, onlyNoodle)).toBeNull();
    expect(prunedPending(pending(two), 'store_other', TOKEN, onlyNoodle)).toBeNull();
  });

  it('沒有留著的記錄就沒得修剪', () => {
    expect(prunedPending(null, STORE, TOKEN, onlyNoodle)).toBeNull();
  });
});

/**
 * 沒有這個判斷的話：同一支手機、同一張桌，上一攤結完帳之後再掃進來，
 * 畫面會跳出一張已經付過的帳單（Bryant 真機實測時問到的）。
 */
describe('sessionEnded', () => {
  const cached = (over: Partial<GuestOrder> = {}): GuestOrder => ({
    orderId: 'order_1',
    sessionId: 'fedcba9876543210fedcba9876543210',
    tableLabel: 'A1',
    status: 'pending_confirm',
    lines: [],
    subtotal: 180,
    serviceCharge: 0,
    discount: 0,
    total: 180,
    ...over,
  });

  const state = (openOrder: TableState['openOrder']): TableState => ({
    tableLabel: 'A1',
    openOrder,
  });

  const open = (mine: boolean) => state({ itemCount: 1, total: 180, status: 'open', mine });

  it('還是自己那一攤就留著', () => {
    expect(sessionEnded(cached(), open(true))).toBe(false);
  });

  it('桌上沒有未結帳的單 = 那一攤結束了', () => {
    expect(sessionEnded(cached(), state(null))).toBe(true);
  });

  it('有單但不是自己那一攤 = 換了一組客人', () => {
    expect(sessionEnded(cached(), open(false))).toBe(true);
  });

  // 斷線時本機這份快照是客人唯一看得到的紀錄，拿不到答案就寧可留著。
  it('讀不到桌況就當作還在同一攤', () => {
    expect(sessionEnded(cached(), null)).toBe(false);
  });

  it('本來就沒有快照就沒什麼好丟的', () => {
    expect(sessionEnded(null, state(null))).toBe(false);
    expect(sessionEnded(null, null)).toBe(false);
  });
});
