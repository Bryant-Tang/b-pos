/**
 * 用 Firestore 文件做的簡易限流（SPEC 第五節〈限流實作〉）。
 *
 * 不引入 Redis：這套架構沒有自架服務可以擺記憶體計數器，而為了每分鐘幾次的限流
 * 去開一台 Memorystore，光是固定月費就超過整個專案的預算（SPEC 第九節）。
 * 代價是每次呼叫多一讀一寫，那是可以接受的。
 *
 * 這是**固定視窗**不是滑動視窗：視窗一到就整個重來，所以最壞情況下
 * 跨視窗邊界可以在極短時間內送出 2 × max 次。對「防止有人手滑連按」與
 * 「擋掉隨手寫的腳本」夠用，真正的門檻是 App Check（SPEC 第四節）。
 * 換成滑動視窗要存每一次的時間戳，文件會隨著上限線性變大，不划算。
 */

import { Timestamp } from 'firebase-admin/firestore';
import type { DocumentReference, Firestore } from 'firebase-admin/firestore';

export interface RateLimitPolicy {
  /** 一個視窗內最多幾次 */
  max: number;
  /** 視窗長度，秒 */
  windowSec: number;
}

export interface RateLimitWindow {
  count: number;
  windowStart: Date;
}

export interface RateLimitDecision {
  allowed: boolean;
  /** 放行時要寫回去的新狀態；擋下時為 null，代表這次不用寫（被擋的請求不該再多花一次寫入） */
  next: RateLimitWindow | null;
  /** 擋下時：還要等幾秒視窗才會重置。放行時為 0 */
  retryAfterSec: number;
}

/**
 * 純函式：由目前的計數狀態與現在時間，決定這次放不放行。
 *
 * 抽成純函式是為了讓邊界（視窗剛好到點、文件壞掉、時鐘倒退）能在毫秒級的單元測試
 * 裡跑完，不必每個 case 都去起 emulator。
 */
export function decideRateLimit(
  current: RateLimitWindow | null,
  policy: RateLimitPolicy,
  now: Date,
): RateLimitDecision {
  const windowMs = policy.windowSec * 1000;
  const elapsed = current === null ? Infinity : now.getTime() - current.windowStart.getTime();

  // elapsed 為負數表示文件上的 windowStart 在未來（時鐘倒退，或有人手動改過）。
  // 當成視窗還沒開始、直接重開一個，不要放著不管——否則那個 uid 會被鎖到未來那個時間。
  const sameWindow = current !== null && elapsed >= 0 && elapsed < windowMs;

  if (!sameWindow) {
    return { allowed: true, next: { count: 1, windowStart: now }, retryAfterSec: 0 };
  }

  if (current.count >= policy.max) {
    return {
      allowed: false,
      next: null,
      retryAfterSec: Math.max(1, Math.ceil((windowMs - elapsed) / 1000)),
    };
  }

  return {
    allowed: true,
    next: { count: current.count + 1, windowStart: current.windowStart },
    retryAfterSec: 0,
  };
}

/** 讀出計數文件；欄位缺漏或型別不對一律當成「沒有視窗」，下一次呼叫就重開一個。 */
export function readRateLimitWindow(data: unknown): RateLimitWindow | null {
  const raw = data as { count?: unknown; windowStart?: unknown } | undefined;
  if (!raw) return null;
  const { count, windowStart } = raw;
  if (typeof count !== 'number' || !Number.isInteger(count) || count < 0) return null;
  if (!(windowStart instanceof Timestamp)) return null;
  return { count, windowStart: windowStart.toDate() };
}

/**
 * 在 transaction 裡遞增計數並回報這次能不能放行。
 *
 * 一定要在 transaction 裡：同一個 uid 同時送出兩次的話，各自讀到 count=4 再各自寫 5，
 * 上限 5 就會變成放行 6 次。
 */
export async function consumeRateLimit(
  db: Firestore,
  ref: DocumentReference,
  policy: RateLimitPolicy,
  now: Date,
): Promise<RateLimitDecision> {
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const decision = decideRateLimit(readRateLimitWindow(snap.data()), policy, now);
    if (decision.next !== null) {
      tx.set(ref, {
        count: decision.next.count,
        windowStart: Timestamp.fromDate(decision.next.windowStart),
      });
    }
    return decision;
  });
}
