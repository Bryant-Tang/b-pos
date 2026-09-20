import { describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { OrderIntentSchema } from '../../src/orders/intentSchema.js';

// 全部是虛構資料（見 CLAUDE.md）
function intent(over: Record<string, unknown> = {}) {
  return {
    intentId: 'intent_1',
    orderId: 'order_1',
    orderType: 'dine_in',
    tableId: 'table_1',
    lines: [{ itemId: 'item_beef_noodle', qty: 1, options: [] }],
    createdBy: 'uid_staff',
    clientCreatedAt: Timestamp.fromMillis(1_758_000_000_000),
    ...over,
  };
}

const ok = (v: unknown) => OrderIntentSchema.safeParse(v).success;

describe('OrderIntentSchema', () => {
  it('合法的內用意圖通過', () => {
    expect(ok(intent())).toBe(true);
  });

  it('外帶與候位不帶桌號', () => {
    expect(ok(intent({ orderType: 'takeout', tableId: null }))).toBe(true);
    expect(ok(intent({ orderType: 'waitlist', tableId: null }))).toBe(true);
  });

  describe('金額欄位一律擋掉', () => {
    // .strict() 的重點：不是忽略，是讓整筆驗證失敗。
    for (const field of ['total', 'subtotal', 'price', 'unitPrice', 'discount', 'serviceCharge']) {
      it(`頂層多一個 ${field} 就不通過`, () => {
        expect(ok(intent({ [field]: 0 }))).toBe(false);
      });
    }

    it('藏在 line 裡的金額欄位也擋掉', () => {
      const bad = intent({
        lines: [{ itemId: 'item_beef_noodle', qty: 1, options: [], unitPriceDineIn: 1 }],
      });
      expect(ok(bad)).toBe(false);
    });

    it('藏在選項裡的金額欄位也擋掉', () => {
      const bad = intent({
        lines: [
          {
            itemId: 'item_beef_noodle',
            qty: 1,
            options: [{ groupId: 'grp_spicy', optionId: 'opt_mild', priceDelta: -999 }],
          },
        ],
      });
      expect(ok(bad)).toBe(false);
    });
  });

  describe('型態與桌號要對得起來', () => {
    it('內用沒桌號不通過', () => {
      expect(ok(intent({ tableId: null }))).toBe(false);
    });

    it('外帶帶桌號不通過', () => {
      expect(ok(intent({ orderType: 'takeout', tableId: 'table_1' }))).toBe(false);
    });

    it('不認得的型態不通過', () => {
      expect(ok(intent({ orderType: 'free_meal' }))).toBe(false);
    });
  });

  describe('數量與筆數', () => {
    it('數量必須是正整數', () => {
      for (const qty of [0, -1, 1.5, '1']) {
        expect(ok(intent({ lines: [{ itemId: 'item_beef_noodle', qty, options: [] }] }))).toBe(false);
      }
    });

    it('空的 lines 不通過', () => {
      expect(ok(intent({ lines: [] }))).toBe(false);
    });

    it('超過 100 筆不通過', () => {
      const lines = Array.from({ length: 101 }, () => ({
        itemId: 'item_beef_noodle',
        qty: 1,
        options: [],
      }));
      expect(ok(intent({ lines }))).toBe(false);
    });
  });

  it('缺欄位不通過', () => {
    for (const field of ['intentId', 'orderId', 'orderType', 'tableId', 'lines', 'createdBy', 'clientCreatedAt']) {
      const partial: Record<string, unknown> = intent();
      delete partial[field];
      expect(ok(partial), `少了 ${field} 應該不通過`).toBe(false);
    }
  });

  it('clientCreatedAt 必須是 Timestamp，不能是字串或數字', () => {
    expect(ok(intent({ clientCreatedAt: '2026-09-20T12:00:00Z' }))).toBe(false);
    expect(ok(intent({ clientCreatedAt: 1_758_000_000_000 }))).toBe(false);
    expect(ok(intent({ clientCreatedAt: new Date() }))).toBe(false);
  });
});
