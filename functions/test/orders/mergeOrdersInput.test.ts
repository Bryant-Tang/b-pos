import { randomUUID } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { MergeOrdersInput } from '../../src/orders/mergeOrdersInput.js';

const base = () => ({
  targetOrderId: 'order_1',
  sourceOrderIds: ['order_2'],
  requestId: randomUUID(),
});

const parse = (data: unknown) => MergeOrdersInput.safeParse(data);

describe('MergeOrdersInput', () => {
  it('最小輸入就過', () => {
    expect(parse(base()).success).toBe(true);
  });

  it('可以一次併好幾張', () => {
    const result = parse({ ...base(), sourceOrderIds: ['order_2', 'order_3', 'order_4'] });
    expect(result.success).toBe(true);
  });

  it('來源單不能是空的', () => {
    expect(parse({ ...base(), sourceOrderIds: [] }).success).toBe(false);
  });

  it('一次最多九張', () => {
    const nine = Array.from({ length: 9 }, (_, i) => `order_${i + 2}`);
    expect(parse({ ...base(), sourceOrderIds: nine }).success).toBe(true);
    expect(parse({ ...base(), sourceOrderIds: [...nine, 'order_11'] }).success).toBe(false);
  });

  it('目標單不能同時列在來源單裡', () => {
    // 允許的話，目標單的品項會被算兩遍，客人被多收一份錢。
    expect(parse({ ...base(), sourceOrderIds: ['order_1'] }).success).toBe(false);
  });

  it('同一張來源單不可以列兩次', () => {
    // 同上：重複的那張會被合併兩次。
    expect(parse({ ...base(), sourceOrderIds: ['order_2', 'order_2'] }).success).toBe(false);
  });

  it('requestId 必須是 uuid', () => {
    expect(parse({ ...base(), requestId: 'merge-1' }).success).toBe(false);
  });

  // 這是整份 schema 的重點，與其他店員端 schema 同一個理由：
  // storeId 由 token 決定，輸入裡帶得動就等於可以併別家店的單。
  it('不接受 storeId', () => {
    expect(parse({ ...base(), storeId: 'store_other' }).success).toBe(false);
  });

  it('偷塞金額欄位一律失敗，不是被忽略', () => {
    for (const extra of [{ total: 0 }, { discount: 100 }, { subtotal: 0 }]) {
      expect(parse({ ...base(), ...extra }).success).toBe(false);
    }
  });
});
