import { describe, expect, it } from 'vitest';
import {
  DEFAULT_BUSINESS_SETTINGS,
  MAX_PICKUP_CODE,
  businessDateOf,
  formatPickupCode,
} from '../../src/orders/businessDate.js';

// 台灣全年 UTC+8，沒有日光節約，所以下面一律用 +08:00 寫死本地時間。
const taipei = (local: string) => new Date(`${local}+08:00`);

describe('businessDateOf', () => {
  it('營業時間內的單算當天', () => {
    expect(businessDateOf(taipei('2026-09-20T18:30:00'))).toBe('2026-09-20');
  });

  it('凌晨兩點半的單算前一天', () => {
    expect(businessDateOf(taipei('2026-09-21T02:30:00'))).toBe('2026-09-20');
  });

  it('換日時間整點算當天', () => {
    expect(businessDateOf(taipei('2026-09-21T05:00:00'))).toBe('2026-09-21');
  });

  it('換日前一分鐘算前一天', () => {
    expect(businessDateOf(taipei('2026-09-21T04:59:00'))).toBe('2026-09-20');
  });

  it('跨月也要退對', () => {
    expect(businessDateOf(taipei('2026-10-01T03:00:00'))).toBe('2026-09-30');
  });

  it('跨年也要退對', () => {
    expect(businessDateOf(taipei('2027-01-01T02:00:00'))).toBe('2026-12-31');
  });

  it('閏日也要退對', () => {
    expect(businessDateOf(taipei('2028-03-01T01:00:00'))).toBe('2028-02-29');
  });

  it('換日時間設 0 就等於日曆日', () => {
    const settings = { dayCloseHour: 0, timeZone: 'Asia/Taipei' };
    expect(businessDateOf(taipei('2026-09-21T00:00:00'), settings)).toBe('2026-09-21');
    expect(businessDateOf(taipei('2026-09-21T23:59:00'), settings)).toBe('2026-09-21');
  });

  it('時區真的有作用', () => {
    // 同一個時刻，台北已經是 21 日早上 6 點（營業日 21 日），
    // 而 UTC 還是 20 日晚上 10 點（營業日 20 日）。
    const at = new Date('2026-09-20T22:00:00Z');
    expect(businessDateOf(at, { dayCloseHour: 5, timeZone: 'Asia/Taipei' })).toBe('2026-09-21');
    expect(businessDateOf(at, { dayCloseHour: 5, timeZone: 'UTC' })).toBe('2026-09-20');
  });

  it('有日光節約的時區，跨換日也不會偏掉', () => {
    // 美東 2026-03-08 凌晨 2 點跳到 3 點。當天凌晨 1:30（換日前）應該算 3 月 7 日。
    const settings = { dayCloseHour: 5, timeZone: 'America/New_York' };
    expect(businessDateOf(new Date('2026-03-08T06:30:00Z'), settings)).toBe('2026-03-07');
    // 同日上午 10 點（EDT，UTC-4）算 3 月 8 日。
    expect(businessDateOf(new Date('2026-03-08T14:00:00Z'), settings)).toBe('2026-03-08');
  });

  it('預設是凌晨五點換日、台北時區', () => {
    expect(DEFAULT_BUSINESS_SETTINGS).toEqual({ dayCloseHour: 5, timeZone: 'Asia/Taipei' });
  });

  it('換日時間不合法就拋錯', () => {
    for (const hour of [-1, 24, 5.5, Number.NaN]) {
      expect(() => businessDateOf(new Date(), { dayCloseHour: hour, timeZone: 'Asia/Taipei' })).toThrow(
        RangeError,
      );
    }
  });
});

describe('formatPickupCode', () => {
  it('從 0001 開始，補滿 4 碼', () => {
    expect(formatPickupCode(1)).toBe('0001');
    expect(formatPickupCode(42)).toBe('0042');
    expect(formatPickupCode(413)).toBe('0413');
    expect(formatPickupCode(9999)).toBe('9999');
  });

  it('超過 9999 繞回 0001', () => {
    expect(formatPickupCode(MAX_PICKUP_CODE + 1)).toBe('0001');
    expect(formatPickupCode(MAX_PICKUP_CODE + 2)).toBe('0002');
  });

  it('一整輪都是 4 碼且不重複', () => {
    const codes = new Set<string>();
    for (let n = 1; n <= MAX_PICKUP_CODE; n += 1) {
      const code = formatPickupCode(n);
      expect(code).toHaveLength(4);
      codes.add(code);
    }
    expect(codes.size).toBe(MAX_PICKUP_CODE);
  });

  it('序數不合法就拋錯', () => {
    for (const n of [0, -1, 1.5]) {
      expect(() => formatPickupCode(n)).toThrow(RangeError);
    }
  });
});
