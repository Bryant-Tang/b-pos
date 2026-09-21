import { describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import {
  decideRateLimit,
  readRateLimitWindow,
  type RateLimitPolicy,
} from '../../src/orders/rateLimit.js';

const POLICY: RateLimitPolicy = { max: 5, windowSec: 60 };

const T0 = new Date('2026-09-20T19:00:00+08:00');
const at = (sec: number) => new Date(T0.getTime() + sec * 1000);

describe('decideRateLimit', () => {
  it('沒有計數文件時放行，並開一個新視窗', () => {
    const d = decideRateLimit(null, POLICY, T0);
    expect(d.allowed).toBe(true);
    expect(d.next).toEqual({ count: 1, windowStart: T0 });
  });

  it('同一個視窗內遞增，視窗起點不動', () => {
    const d = decideRateLimit({ count: 2, windowStart: T0 }, POLICY, at(30));
    expect(d.allowed).toBe(true);
    expect(d.next).toEqual({ count: 3, windowStart: T0 });
  });

  it('剛好用到上限時仍放行', () => {
    expect(decideRateLimit({ count: 4, windowStart: T0 }, POLICY, at(10)).allowed).toBe(true);
  });

  it('超過上限擋下，而且不再寫入', () => {
    const d = decideRateLimit({ count: 5, windowStart: T0 }, POLICY, at(10));
    expect(d.allowed).toBe(false);
    // 被擋下的請求不該再花一次寫入：擋下來的目的就是少花資源。
    expect(d.next).toBeNull();
    expect(d.retryAfterSec).toBe(50);
  });

  it('擋下時的 retryAfterSec 最少是 1 秒，不會回 0 讓前端立刻重打', () => {
    const d = decideRateLimit({ count: 5, windowStart: T0 }, POLICY, at(59.9));
    expect(d.allowed).toBe(false);
    expect(d.retryAfterSec).toBe(1);
  });

  it('視窗剛好到點就重開一個', () => {
    const d = decideRateLimit({ count: 5, windowStart: T0 }, POLICY, at(60));
    expect(d.allowed).toBe(true);
    expect(d.next).toEqual({ count: 1, windowStart: at(60) });
  });

  // 固定視窗的已知性質，寫成測試是為了避免有人把它當成 bug 來「修」：
  // 跨視窗邊界最壞情況可以在很短時間內送出 2 × max 次。真正的門檻是 App Check。
  it('跨視窗邊界會放行兩倍的量，這是固定視窗的已知取捨', () => {
    expect(decideRateLimit({ count: 5, windowStart: T0 }, POLICY, at(59)).allowed).toBe(false);
    expect(decideRateLimit({ count: 5, windowStart: T0 }, POLICY, at(61)).allowed).toBe(true);
  });

  it('windowStart 在未來（時鐘倒退）就重開視窗，不會把人鎖到未來', () => {
    const d = decideRateLimit({ count: 5, windowStart: at(3600) }, POLICY, T0);
    expect(d.allowed).toBe(true);
    expect(d.next).toEqual({ count: 1, windowStart: T0 });
  });
});

describe('readRateLimitWindow', () => {
  it('讀得出正常的文件', () => {
    const data = { count: 3, windowStart: Timestamp.fromDate(T0) };
    expect(readRateLimitWindow(data)).toEqual({ count: 3, windowStart: T0 });
  });

  // 壞掉的計數文件不該讓人點不了餐，重開一個視窗就好。
  for (const [name, data] of [
    ['文件不存在', undefined],
    ['缺 count', { windowStart: Timestamp.fromDate(T0) }],
    ['缺 windowStart', { count: 3 }],
    ['count 不是整數', { count: 1.5, windowStart: Timestamp.fromDate(T0) }],
    ['count 是負數', { count: -1, windowStart: Timestamp.fromDate(T0) }],
    ['windowStart 是字串', { count: 1, windowStart: '2026-09-20' }],
  ] as const) {
    it(`${name} 時回 null`, () => {
      expect(readRateLimitWindow(data)).toBeNull();
    });
  }
});
