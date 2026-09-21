import { describe, expect, it } from 'vitest';
import { TableStateInput } from '../../src/orders/tableStateInput.js';

/** 全部是虛構資料（見 CLAUDE.md 第一節）。 */
const base = () => ({
  storeId: 'store_demo',
  tableToken: '0123456789abcdef0123456789abcdef',
});

const ok = (v: unknown) => TableStateInput.safeParse(v).success;

describe('TableStateInput', () => {
  it('合法輸入通過', () => {
    expect(ok(base())).toBe(true);
  });

  describe('token 的格式要跟 createOrder 完全一致', () => {
    // 一邊認得、另一邊認不得的 token，症狀是客人看得到桌號卻送不出單，最難查。
    it('長度不對不通過', () => {
      expect(ok({ ...base(), tableToken: '0123456789abcdef' })).toBe(false);
      expect(ok({ ...base(), tableToken: '0123456789abcdef0123456789abcdef0' })).toBe(false);
    });

    it('大寫十六進位不通過', () => {
      expect(ok({ ...base(), tableToken: '0123456789ABCDEF0123456789abcdef' })).toBe(false);
    });

    it('非十六進位字元不通過', () => {
      expect(ok({ ...base(), tableToken: 'g123456789abcdef0123456789abcdef' })).toBe(false);
    });

    it('空字串不通過', () => {
      expect(ok({ ...base(), tableToken: '' })).toBe(false);
    });
  });

  it('storeId 不能是空的', () => {
    expect(ok({ ...base(), storeId: '' })).toBe(false);
  });

  // .strict()：偷塞的欄位要讓驗證失敗，不是被忽略。
  it('多帶欄位一律失敗', () => {
    for (const extra of [{ items: [] }, { requestId: 'x' }, { total: 0 }, { tableId: 'table_1' }]) {
      expect(ok({ ...base(), ...extra })).toBe(false);
    }
  });

  it('缺欄位不通過', () => {
    expect(ok({ storeId: 'store_demo' })).toBe(false);
    expect(ok({ tableToken: base().tableToken })).toBe(false);
    expect(ok(null)).toBe(false);
  });

  it('型別不對不通過', () => {
    expect(ok({ ...base(), storeId: 1 })).toBe(false);
    expect(ok({ ...base(), tableToken: 123 })).toBe(false);
  });
});
