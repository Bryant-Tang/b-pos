import { randomUUID } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { VoidOrderLineInput } from '../../src/orders/voidOrderLineInput.js';

const base = () => ({
  orderId: 'order_1',
  lineId: randomUUID(),
  requestId: randomUUID(),
});

const parse = (data: unknown) => VoidOrderLineInput.safeParse(data);

describe('VoidOrderLineInput', () => {
  it('最小輸入就過', () => {
    expect(parse(base()).success).toBe(true);
  });

  it('可以帶作廢原因', () => {
    const result = parse({ ...base(), reason: '客人不要了' });
    expect(result.success && result.data.reason).toBe('客人不要了');
  });

  it('原因前後的空白會修掉', () => {
    const result = parse({ ...base(), reason: '  上錯桌  ' });
    expect(result.success && result.data.reason).toBe('上錯桌');
  });

  it('只有空白的原因不算原因', () => {
    expect(parse({ ...base(), reason: '   ' }).success).toBe(false);
  });

  it('原因太長擋掉', () => {
    expect(parse({ ...base(), reason: 'x'.repeat(101) }).success).toBe(false);
  });

  // 這是整份 schema 的重點：storeId 由 token 決定，輸入裡帶得動就等於可以指定別家店。
  it('不接受 storeId', () => {
    expect(parse({ ...base(), storeId: 'store_other' }).success).toBe(false);
  });

  // .strict() 要讓偷塞的金額欄位「失敗」而不是「被忽略」（CLAUDE.md 第二節第一條）。
  it('偷塞金額欄位一律失敗，不是被忽略', () => {
    for (const extra of [{ total: 0 }, { subtotal: 0 }, { discount: 100 }, { price: 1 }]) {
      expect(parse({ ...base(), ...extra }).success).toBe(false);
    }
  });

  it('requestId 必填，而且要是 UUID', () => {
    const { requestId: _drop, ...without } = base();
    expect(parse(without).success).toBe(false);
    expect(parse({ ...base(), requestId: 'abc' }).success).toBe(false);
  });

  it('orderId 與 lineId 不能是空字串', () => {
    expect(parse({ ...base(), orderId: '' }).success).toBe(false);
    expect(parse({ ...base(), lineId: '' }).success).toBe(false);
  });

  it('型別不對擋掉', () => {
    expect(parse({ ...base(), orderId: 1 }).success).toBe(false);
    expect(parse({ ...base(), reason: 1 }).success).toBe(false);
    expect(parse(null).success).toBe(false);
  });
});
