/**
 * 存在客人瀏覽器裡的東西。
 *
 * 三件事，理由各自不同：
 *
 * 1. **sessionId**：客人這一桌這一攤的憑證（SPEC 第十三節〈三個憑證〉）。
 * 2. **最後一次看到的訂單**：重新整理之後還看得到已點項目。這是**本機快照不是即時資料**
 *    ——`orders` 對顧客是讀不到的（Rules 要 staff claim），目前只有 createOrder 的回傳值
 *    帶得到訂單內容。所以店員在平板上改了單，這裡不會變。補一支讀取用的 function
 *    之後就可以換掉（記在 docs/decisions/0005-guest-web.md）。
 * 3. **還沒確認送出成功的 requestId**：冪等鍵。送出後沒收到回應就重按，必須用**同一個**
 *    requestId，伺服器才認得出那是同一次送出而不是再點一份。每次按送出就換一個新的，
 *    等於把伺服器那道冪等保護整個廢掉。
 *
 * localStorage 在無痕視窗會是空的，iOS Safari 也有 7 天上限（SPEC 第十三節）。
 * 當餐不受影響，隔天查詢要靠結帳時給的 4 碼 lookupCode。所以這裡每一個讀寫都要能
 * 安靜地失敗：存不進去只是少了方便，不該讓客人連菜單都看不到。
 */

import type { GuestOrder } from './api.js';

const KEY = 'b-pos.guest.v1';

interface Stored {
  storeId: string;
  tableToken: string;
  sessionId: string;
  order: GuestOrder;
  savedAt: number;
}

interface PendingRequest {
  storeId: string;
  tableToken: string;
  requestId: string;
}

const PENDING_KEY = 'b-pos.guest.pending.v1';

function read<T>(key: string): T | null {
  try {
    const raw = window.localStorage.getItem(key);
    return raw === null ? null : (JSON.parse(raw) as T);
  } catch {
    // 無痕視窗、關掉網站資料、或存進去的是上一版的格式。當作沒有就好。
    return null;
  }
}

function write(key: string, value: unknown): void {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // 配額滿或被擋。存不下來只是重新整理後看不到已點項目，不影響點餐。
  }
}

function drop(key: string): void {
  try {
    window.localStorage.removeItem(key);
  } catch {
    // 同上
  }
}

/** 讀回這張桌上次的訂單。換了一張桌（token 不同）就當作沒有，不要顯示別桌的單。 */
export function loadOrder(storeId: string, tableToken: string): GuestOrder | null {
  const stored = read<Stored>(KEY);
  if (stored === null) return null;
  if (stored.storeId !== storeId || stored.tableToken !== tableToken) return null;
  return stored.order;
}

export function saveOrder(storeId: string, tableToken: string, order: GuestOrder): void {
  write(KEY, { storeId, tableToken, sessionId: order.sessionId, order, savedAt: Date.now() });
}

export function clearOrder(): void {
  drop(KEY);
}

/**
 * 這一次送出要用的 requestId。
 *
 * 同一張桌上還沒送成功的那一個會被重複拿出來用，這正是冪等要的：
 * 網路斷在回應路上、客人再按一次，伺服器認得出是同一次送出，不會變成點兩份。
 */
export function takeRequestId(storeId: string, tableToken: string): string {
  const pending = read<PendingRequest>(PENDING_KEY);
  if (pending !== null && pending.storeId === storeId && pending.tableToken === tableToken) {
    return pending.requestId;
  }
  const requestId = crypto.randomUUID();
  write(PENDING_KEY, { storeId, tableToken, requestId });
  return requestId;
}

/** 送出成功（或確定失敗到不該再重試）之後才丟掉，下一次按送出就是新的一次。 */
export function clearRequestId(): void {
  drop(PENDING_KEY);
}
