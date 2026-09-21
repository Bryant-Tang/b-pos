import { randomUUID } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { MoveOrderTableInput } from '../../src/orders/moveOrderTableInput.js';

const base = () => ({
  orderId: 'order_1',
  fromTableId: 'table_1',
  toTableId: 'table_2',
  requestId: randomUUID(),
});

const parse = (data: unknown) => MoveOrderTableInput.safeParse(data);

describe('MoveOrderTableInput', () => {
  it('最小輸入就過', () => {
    expect(parse(base()).success).toBe(true);
  });

  it('四個欄位都是必填', () => {
    for (const key of ['orderId', 'fromTableId', 'toTableId', 'requestId'] as const) {
      const data: Record<string, unknown> = { ...base() };
      delete data[key];
      expect(parse(data).success).toBe(false);
    }
  });

  it('空字串的桌號不算桌號', () => {
    expect(parse({ ...base(), fromTableId: '' }).success).toBe(false);
    expect(parse({ ...base(), toTableId: '' }).success).toBe(false);
  });

  it('requestId 必須是 uuid', () => {
    // 不是挑剔格式：平板端自己編的字串（例如桌號加時間）在重送時未必一樣，
    // 冪等就失效了。限定 uuid 是逼呼叫端真的產生一個一次性的鍵。
    expect(parse({ ...base(), requestId: 'move-1' }).success).toBe(false);
  });

  // 這是整份 schema 的重點，與 VoidOrderLineInput 同一個理由：
  // storeId 由 token 決定，輸入裡帶得動就等於可以對別家店的單轉桌。
  it('不接受 storeId', () => {
    expect(parse({ ...base(), storeId: 'store_other' }).success).toBe(false);
  });

  it('偷塞的欄位一律失敗，不是被忽略', () => {
    for (const extra of [{ total: 0 }, { tableIds: ['table_9'] }, { status: 'open' }]) {
      expect(parse({ ...base(), ...extra }).success).toBe(false);
    }
  });
});
