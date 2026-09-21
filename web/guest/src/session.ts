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
 * 3. **還沒確認送出成功的那一次送出**：冪等鍵加上當時送的內容。送出後沒收到回應就重按，
 *    必須用**同一個** requestId，伺服器才認得出那是同一次送出而不是再點一份。每次按送出
 *    就換一個新的，等於把伺服器那道冪等保護整個廢掉。
 *
 *    但**只存 requestId 不存內容會反過來吃掉訂單**：送出成功、回應在路上掉了、
 *    分頁被系統回收，客人重新整理之後購物車是空的（React state 沒了），
 *    重點一份別的再送出——沿用同一個 requestId，伺服器認出那個鍵用過了，
 *    於是原樣回傳舊訂單，新點的那份**從來沒有進到伺服器，而且沒有任何錯誤**。
 *    漏單比重複下單嚴重得多：重複下單只是店員多滑一次（SPEC 第五節），
 *    漏單是客人真的少了一份餐。
 *
 *    所以內容要跟著 requestId 一起存：內容一樣才沿用（那才真的是同一次送出），
 *    不一樣就換一個新的。重新整理時也把購物車還原回來，客人不必重點一次。
 *
 * localStorage 在無痕視窗會是空的，iOS Safari 也有 7 天上限（SPEC 第十三節）。
 * 當餐不受影響，隔天查詢要靠結帳時給的 4 碼 lookupCode。所以這裡每一個讀寫都要能
 * 安靜地失敗：存不進去只是少了方便，不該讓客人連菜單都看不到。
 */

import type { GuestOrder } from './api.js';
import type { CartLine } from './cart.js';

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
  /** 當時送出的內容，用來判斷「這是同一次送出」還是「另一次」 */
  fingerprint: string;
  /** 當時的購物車，重新整理後還原用 */
  lines: CartLine[];
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
 * 購物車內容的指紋。
 *
 * 只看「點了什麼、幾份」——品項與選項的 ID 都在 `key` 裡，而顯示用的名稱與價格
 * 不影響「這是不是同一次送出」。排序過，所以加入順序不同不算不同。
 */
export function cartFingerprint(lines: readonly CartLine[]): string {
  return lines
    .map((line) => `${line.key}x${line.qty}`)
    .sort()
    .join('|');
}

/**
 * 這一次送出該不該沿用上一個還沒確認的 requestId。純函式，好測。
 *
 * 沿用的條件是**同一張桌而且內容一模一樣**。內容不一樣就是另一次送出，
 * 沿用的話伺服器會把它當重送、原樣回傳舊訂單，這次點的東西就無聲無息地不見了。
 */
export function reusableRequestId(
  pending: PendingRequest | null,
  storeId: string,
  tableToken: string,
  fingerprint: string,
): string | null {
  if (pending === null) return null;
  if (pending.storeId !== storeId || pending.tableToken !== tableToken) return null;
  if (pending.fingerprint !== fingerprint) return null;
  return pending.requestId;
}

/** 這一次送出要用的 requestId，同時把內容存下來。 */
export function takeRequestId(
  storeId: string,
  tableToken: string,
  lines: readonly CartLine[],
): string {
  const fingerprint = cartFingerprint(lines);
  const reused = reusableRequestId(
    read<PendingRequest>(PENDING_KEY),
    storeId,
    tableToken,
    fingerprint,
  );
  if (reused !== null) return reused;

  const requestId = crypto.randomUUID();
  write(PENDING_KEY, { storeId, tableToken, requestId, fingerprint, lines: [...lines] });
  return requestId;
}

/**
 * 上一次送出沒收到回應時留下來的購物車。
 *
 * 還原它，客人才不用把剛剛點的東西重點一遍；而且還原之後按下送出，內容一樣，
 * 就會沿用同一個 requestId——那次送出如果其實已經成功，伺服器會原樣回傳那張單，
 * 不會變成點兩份。
 */
export function loadPendingCart(storeId: string, tableToken: string): CartLine[] | null {
  const pending = read<PendingRequest>(PENDING_KEY);
  if (pending === null) return null;
  if (pending.storeId !== storeId || pending.tableToken !== tableToken) return null;
  return Array.isArray(pending.lines) && pending.lines.length > 0 ? pending.lines : null;
}

/** 送出成功（或確定失敗到不該再重試）之後才丟掉，下一次按送出就是新的一次。 */
export function clearRequestId(): void {
  drop(PENDING_KEY);
}
