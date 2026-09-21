import { randomUUID } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { CloseOrderInput } from '../../src/orders/closeOrderInput.js';

const base = () => ({
  orderId: 'order_1',
  payment: { method: 'cash' as const, received: 500 },
  requestId: randomUUID(),
});

const parse = (data: unknown) => CloseOrderInput.safeParse(data);

describe('CloseOrderInput', () => {
  it('付現帶收到的金額就過', () => {
    expect(parse(base()).success).toBe(true);
  });

  it('付現也可以不填收到的金額（剛好給整數、不用找零）', () => {
    expect(parse({ ...base(), payment: { method: 'cash' } }).success).toBe(true);
  });

  it('刷卡與行動支付都過', () => {
    for (const method of ['card', 'mobile']) {
      expect(parse({ ...base(), payment: { method } }).success).toBe(true);
    }
  });

  it('刷卡與行動支付不可以帶收到的金額', () => {
    // 那兩種收的就是總額，帶進來只會讓「找零」出現在不該出現的地方。
    for (const method of ['card', 'mobile']) {
      expect(parse({ ...base(), payment: { method, received: 500 } }).success).toBe(false);
    }
  });

  it('沒聽過的付款方式擋下來', () => {
    expect(parse({ ...base(), payment: { method: 'crypto' } }).success).toBe(false);
  });

  it('收到的金額必須是非負整數', () => {
    for (const received of [-100, 12.5]) {
      expect(parse({ ...base(), payment: { method: 'cash', received } }).success).toBe(false);
    }
  });

  it('requestId 必須是 uuid', () => {
    expect(parse({ ...base(), requestId: 'close-1' }).success).toBe(false);
  });

  // 這是整份 schema 的重點，與其他店員端 schema 同一個理由：
  // storeId 由 token 決定，輸入裡帶得動就等於可以結別家店的單。
  it('不接受 storeId', () => {
    expect(parse({ ...base(), storeId: 'store_other' }).success).toBe(false);
  });

  it('偷塞金額欄位一律失敗，不是被忽略', () => {
    // 總額由伺服器從單上的品項算出來鎖住，客戶端一個字都不能帶（CLAUDE.md 第二節第一條）。
    for (const extra of [{ total: 0 }, { subtotal: 0 }, { discount: 100 }]) {
      expect(parse({ ...base(), ...extra }).success).toBe(false);
    }
    expect(parse({ ...base(), payment: { method: 'cash', received: 500, change: 0 } }).success).toBe(
      false,
    );
  });
});
