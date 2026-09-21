import { describe, expect, it } from 'vitest';
import { CreateOrderInput } from '../../src/orders/createOrderInput.js';

// 全部是虛構資料（見 CLAUDE.md）。token 是隨手編的 32 碼十六進位，不對應任何真實桌位。
const TOKEN = '0123456789abcdef0123456789abcdef';
const REQUEST_ID = '11111111-2222-4333-8444-555555555555';

function input(over: Record<string, unknown> = {}) {
  return {
    storeId: 'store_demo',
    tableToken: TOKEN,
    requestId: REQUEST_ID,
    items: [{ itemId: 'item_beef_noodle', qty: 1, options: [] }],
    ...over,
  };
}

const ok = (v: unknown) => CreateOrderInput.safeParse(v).success;

describe('CreateOrderInput', () => {
  it('合法的請求通過', () => {
    expect(ok(input())).toBe(true);
  });

  it('options 可以省略，預設空陣列', () => {
    const parsed = CreateOrderInput.parse(input({ items: [{ itemId: 'item_rice', qty: 2 }] }));
    expect(parsed.items[0]?.options).toEqual([]);
  });

  describe('金額欄位一律擋掉', () => {
    // 三條架構原則的第一條：客戶端永遠不送金額（CLAUDE.md 第二節）。
    // .strict() 的重點是「讓整筆失敗」，不是「忽略多餘欄位」。
    for (const field of ['total', 'subtotal', 'price', 'unitPrice', 'discount', 'serviceCharge']) {
      it(`頂層多一個 ${field} 就不通過`, () => {
        expect(ok(input({ [field]: 0 }))).toBe(false);
      });
    }

    it('藏在品項裡的金額欄位也擋掉', () => {
      expect(ok(input({ items: [{ itemId: 'item_rice', qty: 1, options: [], price: 0 }] }))).toBe(
        false,
      );
    });

    it('藏在選項裡的金額欄位也擋掉', () => {
      const items = [
        {
          itemId: 'item_rice',
          qty: 1,
          options: [{ groupId: 'grp_remove', optionId: 'opt_no_meat', priceDelta: -999 }],
        },
      ];
      expect(ok(input({ items }))).toBe(false);
    });
  });

  describe('tableToken 必須是 createTable 產生的形狀', () => {
    it('32 碼小寫十六進位通過', () => {
      expect(ok(input({ tableToken: 'a'.repeat(32) }))).toBe(true);
    });

    for (const [name, token] of [
      ['太短', 'abc'],
      ['太長', '0'.repeat(33)],
      ['不是十六進位', 'z'.repeat(32)],
      ['大寫', 'A'.repeat(32)],
      ['可預測的桌號', '5'],
      ['空字串', ''],
    ] as const) {
      it(`${name} 不通過`, () => {
        expect(ok(input({ tableToken: token }))).toBe(false);
      });
    }

    it('不是字串不通過', () => {
      expect(ok(input({ tableToken: 5 }))).toBe(false);
    });
  });

  describe('數量與筆數上限', () => {
    it('數量 0 或負數不通過', () => {
      expect(ok(input({ items: [{ itemId: 'item_rice', qty: 0, options: [] }] }))).toBe(false);
      expect(ok(input({ items: [{ itemId: 'item_rice', qty: -1, options: [] }] }))).toBe(false);
    });

    it('數量不是整數不通過', () => {
      expect(ok(input({ items: [{ itemId: 'item_rice', qty: 1.5, options: [] }] }))).toBe(false);
    });

    it('單一品項最多 20 份', () => {
      expect(ok(input({ items: [{ itemId: 'item_rice', qty: 20, options: [] }] }))).toBe(true);
      expect(ok(input({ items: [{ itemId: 'item_rice', qty: 21, options: [] }] }))).toBe(false);
    });

    it('一張單最多 50 筆，且不能是空的', () => {
      const line = { itemId: 'item_rice', qty: 1, options: [] };
      expect(ok(input({ items: [] }))).toBe(false);
      expect(ok(input({ items: Array.from({ length: 50 }, () => line) }))).toBe(true);
      expect(ok(input({ items: Array.from({ length: 51 }, () => line) }))).toBe(false);
    });

    it('一個品項最多 10 個選項', () => {
      const option = { groupId: 'grp_remove', optionId: 'opt_no_meat' };
      const withOptions = (n: number) => [
        { itemId: 'item_rice', qty: 1, options: Array.from({ length: n }, () => option) },
      ];
      expect(ok(input({ items: withOptions(10) }))).toBe(true);
      expect(ok(input({ items: withOptions(11) }))).toBe(false);
    });
  });

  describe('requestId 是冪等鍵，必填且必須是 UUID', () => {
    it('省略不通過——選填等於讓忘了帶的客戶端安靜地失去保護', () => {
      const { requestId: _omitted, ...rest } = input();
      expect(ok(rest)).toBe(false);
    });

    it('不是 UUID 不通過', () => {
      expect(ok(input({ requestId: 'req_1' }))).toBe(false);
      expect(ok(input({ requestId: '' }))).toBe(false);
    });
  });

  it('storeId 不可省略或留空', () => {
    expect(ok(input({ storeId: '' }))).toBe(false);
    const { storeId: _omitted, ...rest } = input();
    expect(ok(rest)).toBe(false);
  });
});
