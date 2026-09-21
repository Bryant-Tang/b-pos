import { randomUUID } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { ConfirmGuestOrderInput } from '../../src/orders/confirmGuestOrderInput.js';

const base = () => ({
  orderId: 'order_1',
  requestId: randomUUID(),
});

const parse = (data: unknown) => ConfirmGuestOrderInput.safeParse(data);

describe('ConfirmGuestOrderInput', () => {
  it('最小輸入就過', () => {
    expect(parse(base()).success).toBe(true);
  });

  it('兩個欄位都是必填', () => {
    for (const key of ['orderId', 'requestId'] as const) {
      const data: Record<string, unknown> = { ...base() };
      delete data[key];
      expect(parse(data).success).toBe(false);
    }
  });

  it('空字串的單號不算單號', () => {
    expect(parse({ ...base(), orderId: '' }).success).toBe(false);
  });

  it('requestId 必須是 uuid', () => {
    // 理由同轉桌：平板自己編的字串（例如單號加時間）在重送時未必一樣，
    // 冪等就失效了，而這支失效的後果是廚房收到兩張一樣的單。
    expect(parse({ ...base(), requestId: 'confirm-1' }).success).toBe(false);
  });

  // 這是整份 schema 的重點，與其他店員端 schema 同一個理由：
  // storeId 由 token 決定，輸入裡帶得動就等於可以確認別家店的單。
  it('不接受 storeId', () => {
    expect(parse({ ...base(), storeId: 'store_other' }).success).toBe(false);
  });

  it('偷塞的欄位一律失敗，不是被忽略', () => {
    for (const extra of [{ status: 'open' }, { total: 0 }, { lines: [] }]) {
      expect(parse({ ...base(), ...extra }).success).toBe(false);
    }
  });
});
