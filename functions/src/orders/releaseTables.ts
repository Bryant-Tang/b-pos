/**
 * 把結帳滿三小時的場次收掉，桌位回到空桌（SPEC 第五節 `releaseTables`、
 * 第六節〈狀態轉換規則〉「已結帳 → 空桌：滿 3 小時由 releaseTables 排程自動釋放」）。
 *
 * ## 「釋放桌位」實際上要做什麼
 *
 * 桌位文件上沒有「現在是什麼狀態」這個欄位——顏色是平板從那張桌底下的 session 推出來的
 * （`android/.../tables/TableStatus.kt` 的 `deriveTableStatus`），而結帳當下 `closeOrder`
 * 就已經把桌位的 `activeSessionId` 清掉了。所以這支要收的不是桌位，是**過期的場次與收據**：
 *
 * 1. 刪掉 `readableUntil` 已過的已結帳 session，連同它那份 `receipts/{sessionId}`。
 * 2. 萬一有哪張桌的 `activeSessionId` 還指著這種 session，順手清掉（見下面的防呆）。
 *
 * 不刪的話兩件事會慢慢壞掉：平板每次重連要把整桌歷史場次拉回來（SPEC 第十一節第 2 點
 * 那個「初始快照越來越貴」的問題），而收據雖然過期後 Rules 就不給讀了，文件本身還在。
 *
 * ## 為什麼不靠 TTL policy
 *
 * SPEC 資料模型那裡寫 `readableUntil` 由 TTL policy 自動清除。TTL 是對的長期方案，
 * 但官方講明刪除時間是「過期後 24 小時內」而且不保證——排程每 15 分鐘跑一次，
 * 兩者不衝突：TTL 當作兜底，準時的那一份由這裡負責。
 *
 * ## 時間邊界要跟平板同一邊
 *
 * 平板判斷「還在可讀期」用的是 `readableUntil > now`（剛好到點就算過期），
 * 所以這裡查的是 `readableUntil <= now`。兩邊差一個等號的話，會出現平板顯示已結帳、
 * 伺服器這邊已經把場次刪掉的那一分鐘。TableStatus.kt 的註解寫的就是這件事。
 */

import { Timestamp } from 'firebase-admin/firestore';
import type { DocumentSnapshot, Firestore, QueryDocumentSnapshot } from 'firebase-admin/firestore';
import { tenantRefs } from './orderDocs.js';

/**
 * 一次最多處理幾個場次。
 *
 * 單店一天約 200 單（SPEC 第九節的用量估算），每 15 分鐘跑一次的話正常情況下
 * 每輪是個位數。設上限是為了「排程停了幾天之後第一次跑起來」那種情況：
 * 一次把幾千份文件刪掉會撞到寫入配額，而剩下的下一輪（15 分鐘後）就會接著收，
 * 沒有任何東西會因此漏掉。
 */
const SCAN_LIMIT = 200;

/** 一個 WriteBatch 最多 500 個寫入，每個場次最多兩個（場次本身＋收據）。 */
const SESSIONS_PER_BATCH = 200;

export interface ReleaseTablesResult {
  /** 查到的過期場次數 */
  scanned: number;
  /** 真的刪掉的場次數 */
  releasedSessions: number;
  /** 跟著刪掉的收據數 */
  deletedReceipts: number;
  /** 收據還在可讀期，連同場次一起留到下一輪的數量 */
  keptSessions: number;
  /** 清掉殘留指標的桌位數 */
  clearedTables: number;
}

function isStillReadable(snap: DocumentSnapshot | undefined, now: Timestamp): boolean {
  if (snap === undefined || !snap.exists) return false;
  const expiresAt = snap.get('expiresAt');
  // 與平板同一個邊界：剛好到點就算過期。
  return expiresAt instanceof Timestamp && expiresAt.toMillis() > now.toMillis();
}

function readTableId(snap: QueryDocumentSnapshot): string | null {
  const tableId = snap.get('tableId');
  return typeof tableId === 'string' && tableId.length > 0 ? tableId : null;
}

/**
 * 掃出所有已經過了可讀期的已結帳場次，把它們與對應的收據刪掉。
 *
 * 跨店一次查完：`collectionGroup` 一支查詢就涵蓋所有 `tenants/{storeId}/sessions`，
 * 不必先列舉有哪些店。每一份文件要寫回去的路徑都是從它自己的父文件推回來的
 * （`snap.ref.parent.parent`），所以寫入不會跨到別間店去。
 */
export async function releaseExpiredTables(
  db: Firestore,
  now: Date,
  limit: number = SCAN_LIMIT,
): Promise<ReleaseTablesResult> {
  const nowTs = Timestamp.fromDate(now);
  const expired = await db
    .collectionGroup('sessions')
    .where('status', '==', 'closed')
    .where('readableUntil', '<=', nowTs)
    .orderBy('readableUntil')
    .limit(limit)
    .get();

  const result: ReleaseTablesResult = {
    scanned: expired.size,
    releasedSessions: 0,
    deletedReceipts: 0,
    keptSessions: 0,
    clearedTables: 0,
  };

  for (const [storeId, sessions] of groupByStore(expired.docs)) {
    const tallies = await releaseStore(db, storeId, sessions, nowTs);
    result.releasedSessions += tallies.releasedSessions;
    result.deletedReceipts += tallies.deletedReceipts;
    result.keptSessions += tallies.keptSessions;
    result.clearedTables += tallies.clearedTables;
  }

  return result;
}

/** 依 `tenants/{storeId}` 分組；讀不出店家代號的（路徑形狀不對）直接跳過。 */
function groupByStore(docs: QueryDocumentSnapshot[]): Map<string, QueryDocumentSnapshot[]> {
  const grouped = new Map<string, QueryDocumentSnapshot[]>();
  for (const doc of docs) {
    const storeId = doc.ref.parent.parent?.id;
    if (storeId === undefined || storeId.length === 0) {
      console.warn(`場次 ${doc.ref.path} 不在 tenants/{storeId}/sessions 底下，跳過`);
      continue;
    }
    const bucket = grouped.get(storeId);
    if (bucket === undefined) grouped.set(storeId, [doc]);
    else bucket.push(doc);
  }
  return grouped;
}

async function releaseStore(
  db: Firestore,
  storeId: string,
  sessions: QueryDocumentSnapshot[],
  nowTs: Timestamp,
): Promise<Omit<ReleaseTablesResult, 'scanned'>> {
  const refs = tenantRefs(db, storeId);

  // 收據與場次一起處理，不拆開：收據還看得到、場次卻已經刪掉的話，那份收據就再也
  // 不會被掃到（下一輪的查詢是查場次），會一直留在資料庫裡。兩邊的三小時是
  // closeOrder 在同一個 transaction 裡寫的同一個時間，正常情況下這個分支不會走到。
  const receiptSnaps = await db.getAll(...sessions.map((snap) => refs.receipt(snap.id)));
  const ready: QueryDocumentSnapshot[] = [];
  const receiptsToDelete: QueryDocumentSnapshot[] = [];
  let keptSessions = 0;

  sessions.forEach((session, index) => {
    const receipt = receiptSnaps[index];
    if (isStillReadable(receipt, nowTs)) {
      keptSessions += 1;
      return;
    }
    ready.push(session);
    if (receipt?.exists === true) receiptsToDelete.push(session);
  });

  const clearedTables = await clearStalePointers(db, refs, ready);

  const receiptOwners = new Set(receiptsToDelete.map((snap) => snap.id));
  for (let start = 0; start < ready.length; start += SESSIONS_PER_BATCH) {
    const chunk = ready.slice(start, start + SESSIONS_PER_BATCH);
    const batch = db.batch();
    for (const session of chunk) {
      batch.delete(session.ref);
      if (receiptOwners.has(session.id)) batch.delete(refs.receipt(session.id));
    }
    await batch.commit();
  }

  return {
    releasedSessions: ready.length,
    deletedReceipts: receiptsToDelete.length,
    keptSessions,
    clearedTables,
  };
}

/**
 * 清掉還指著已結帳場次的 `activeSessionId`。
 *
 * 正常情況下一個都不會有——`closeOrder` 結帳當下就清掉了。留這段是因為刪掉場次之後
 * 那個指標會指向一份不存在的文件，而下一個掃這張桌 QR 的客人拿到的就是這個指標
 * （`getTableState`）。那邊有防呆會當成空桌，所以這裡不是修 bug，是不要留下
 * 自相矛盾的資料給下一個讀的人。
 *
 * 先用一次批次讀挑出真的指錯的桌（正常是零張），只有那幾張才開 transaction。
 * **清除一定要在 transaction 裡重讀一次**：同一瞬間可能有新客人正在
 * `createGuestOrder` 裡把自己的場次寫進這個欄位，無條件覆寫成 null 會讓剛坐下
 * 那一組人的桌位變回空桌，下一次掃碼就會開出第二張單。
 */
async function clearStalePointers(
  db: Firestore,
  refs: ReturnType<typeof tenantRefs>,
  sessions: QueryDocumentSnapshot[],
): Promise<number> {
  const expiredIds = new Set(sessions.map((snap) => snap.id));
  const tableIds = [...new Set(sessions.map(readTableId).filter((id) => id !== null))];
  if (tableIds.length === 0) return 0;

  const tableSnaps = await db.getAll(...tableIds.map((id) => refs.table(id)));
  let cleared = 0;

  for (const snap of tableSnaps) {
    if (snap === undefined || !snap.exists) continue;
    const pointer = snap.get('activeSessionId');
    if (typeof pointer !== 'string' || !expiredIds.has(pointer)) continue;

    const changed = await db.runTransaction(async (tx) => {
      const fresh = await tx.get(snap.ref);
      const current = fresh.get('activeSessionId');
      if (typeof current !== 'string' || !expiredIds.has(current)) return false;
      tx.set(snap.ref, { activeSessionId: null }, { merge: true });
      return true;
    });
    if (changed) cleared += 1;
  }

  return cleared;
}
