/**
 * 店員轉桌：把一張還沒結帳的單，從某一桌搬到另一桌。
 *
 * 實際的場景是「這桌太吵，幫我換到窗邊」或是店員一開始按錯桌號。單本身、已點的品項、
 * 金額都不動，動的只有這張單掛在哪一桌。
 *
 * 要一起搬的有三個東西，少搬一個就會出現對不起來的畫面：
 *
 * 1. **訂單上的 `tableIds` / `tableLabels`。** 平板的桌位總覽與訂單列表都靠它，
 *    不改的話新桌點進去是空的、舊桌還顯示有人。
 * 2. **這張單的 session。** 客人手機裡存的是 `sessionId`（SPEC 第十三節：權限判斷
 *    一律掛在 sessionId 上，不掛 UID），session 上記著 `tableId`。不跟著搬，
 *    客人在新桌掃 QR 會看到「這桌沒有單」，而他的帳單還在舊桌上。
 * 3. **兩張桌位文件的 `activeSessionId`。** 那是「這張桌現在有人」的唯一指標，
 *    顧客端 createGuestOrder 就是靠它決定要附加到既有的單還是開新單。舊桌沒清掉，
 *    下一組客人掃進來會加點到已經搬走的那一攤的帳上。
 *
 * 與 voidOrderLine 一樣，刻意只吃 `Firestore` 與已經驗過的輸入，不碰 onCall 的
 * request 物件，這樣 emulator 測試可以直接呼叫。
 */

import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import type { Firestore, Transaction } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import type { StaffCaller } from '../auth/staffAuth.js';
import { tenantRefs } from './orderDocs.js';
import type { MoveOrderTableInput } from './moveOrderTableInput.js';

/**
 * 可以轉桌的訂單狀態。
 *
 * 與退點那一邊（VOIDABLE_STATUSES）是同一組，理由也一樣：結帳後的單已經寫了
 * `businessDate`、搬進 archive、算進當日營收了（SPEC 第十三節〈結帳是硬分界線〉）。
 * 客人結完帳又換桌繼續吃，那是新的一張單，不是把舊的那張拖過來。
 */
const MOVABLE_STATUSES = new Set(['open', 'pending_confirm']);

/**
 * 還佔著桌子的單。判斷目標桌有沒有人在用的時候看的就是這一組。
 *
 * 現在的內容與 MOVABLE_STATUSES 一樣，但刻意分開寫：「這張單還能不能改」與
 * 「這張桌還有沒有人」是兩個問題，哪天加了第三種狀態時不該被迫一起變。
 */
const OCCUPYING_STATUSES = new Set(['open', 'pending_confirm']);

export type MoveOutcome =
  /** 真的搬了 */
  | 'moved'
  /** 同一個 requestId 已經處理過，這次什麼都沒改 */
  | 'already_applied';

export interface MoveOrderTableResult {
  orderId: string;
  fromTableId: string;
  toTableId: string;
  outcome: MoveOutcome;
  /** 搬完以後這張單掛在哪幾桌，讓平板直接更新畫面不必再讀一次。 */
  tableIds: string[];
  tableLabels: string[];
}

interface SessionToMove {
  id: string;
  /** 這個 session 是不是還活著。只有活著的才要改指標。 */
  active: boolean;
}

export async function moveOrderTable(
  db: Firestore,
  input: MoveOrderTableInput,
  caller: StaffCaller,
  now: Date,
): Promise<MoveOrderTableResult> {
  const refs = tenantRefs(db, caller.storeId);
  const nowTs = Timestamp.fromDate(now);

  if (input.fromTableId === input.toTableId) {
    // 不是「已經在那裡了所以算成功」：平板不該送得出這種請求，安靜吞掉只會讓
    // 選錯桌的介面問題一直沒人發現。
    throw new HttpsError('invalid-argument', '來源與目標是同一桌，不需要轉桌');
  }

  return db.runTransaction(async (tx): Promise<MoveOrderTableResult> => {
    const orderRef = refs.order(input.orderId);
    const orderSnap = await tx.get(orderRef);
    if (!orderSnap.exists) {
      throw new HttpsError('not-found', '找不到這張單，請重新整理訂單列表');
    }
    const order = orderSnap.data() ?? {};

    const tableIds = readStringArray(order, 'tableIds');
    const tableLabels = readStringArray(order, 'tableLabels');

    // 冪等要在 transaction 內比對，理由同 voidOrderLine：兩次呼叫同時進來時，
    // 在外面比對會兩邊都讀到「還沒用過」。
    const appliedRequestIds = readStringArray(order, 'appliedRequestIds');
    if (appliedRequestIds.includes(input.requestId)) {
      return {
        orderId: orderRef.id,
        fromTableId: input.fromTableId,
        toTableId: input.toTableId,
        outcome: 'already_applied',
        tableIds,
        tableLabels,
      };
    }

    const status = String(order['status'] ?? '');
    if (!MOVABLE_STATUSES.has(status)) {
      throw new HttpsError(
        'failed-precondition',
        status === 'closed' ? '這張單已經結帳了，不能再轉桌' : '這張單已經作廢，不能轉桌',
      );
    }

    const fromIndex = tableIds.indexOf(input.fromTableId);
    if (fromIndex === -1) {
      // 外帶單（tableIds 是空的）也會走到這裡，訊息一樣說得通：它本來就沒有桌可以轉。
      throw new HttpsError('failed-precondition', '這張單不在那一桌，請重新整理訂單明細');
    }
    if (tableIds.includes(input.toTableId)) {
      // 兩桌都已經在同一張單上（併過桌）。把其中一桌搬到另一桌等於悄悄把併桌拆掉，
      // 結果是那一桌從單上消失、桌位總覽變成空桌，但客人還坐在那裡。
      throw new HttpsError('failed-precondition', '那一桌已經在這張單上了');
    }

    const toTableRef = refs.table(input.toTableId);
    const fromTableRef = refs.table(input.fromTableId);
    // 兩張桌位文件都讀進來，除了要拿標籤，更重要的是讓它們成為這筆 transaction 的
    // 衝突點：兩個店員同時把不同的單轉到同一桌時，後到的那一筆會重跑而不是覆蓋。
    const [toTableSnap] = await tx.getAll(toTableRef, fromTableRef);
    if (toTableSnap === undefined || !toTableSnap.exists || toTableSnap.data()?.['archived'] === true) {
      throw new HttpsError('not-found', '找不到那一桌，請重新整理桌位圖');
    }
    const toLabel = String(toTableSnap.data()?.['label'] ?? '');

    await assertTargetIsFree(tx, refs, input, orderRef.id);

    const sessions = await readOrderSessions(tx, refs, order, input.fromTableId);

    const nextTableIds = [...tableIds];
    nextTableIds[fromIndex] = input.toTableId;
    const nextTableLabels = [...tableLabels];
    // tableLabels 是跟 tableIds 平行的顯示用快照，但舊的單可能根本沒有這個欄位，
    // 或長度對不上（欄位是後來加的）。長度不夠就補到對得上，不要讓標籤錯位——
    // 錯位的結果是訂單明細上寫著另一桌的桌號。
    while (nextTableLabels.length < nextTableIds.length) nextTableLabels.push('');
    nextTableLabels[fromIndex] = toLabel;

    tx.update(orderRef, {
      tableIds: nextTableIds,
      tableLabels: nextTableLabels,
      appliedRequestIds: FieldValue.arrayUnion(input.requestId),
      updatedAt: nowTs,
      // 誰轉的要留痕：對不上帳的時候，「這張單為什麼掛在這一桌」是第一個要問的。
      lastMovedBy: caller.uid,
      lastMovedAt: nowTs,
    });

    const movedSession = sessions.find((s) => s.active);
    for (const session of sessions) {
      if (!session.active) continue;
      tx.update(refs.session(session.id), { tableId: input.toTableId });
    }

    // 店員手開的單沒有 session（applyOrderIntent 建單時 sessionIds 是空的），
    // 那時兩張桌位文件的指標本來就都是空的，不需要動——但還是要把舊桌清乾淨，
    // 因為指標可能是顧客掃碼那一次留下的。
    tx.set(fromTableRef, { activeSessionId: null }, { merge: true });
    if (movedSession !== undefined) {
      tx.set(toTableRef, { activeSessionId: movedSession.id }, { merge: true });
    }

    return {
      orderId: orderRef.id,
      fromTableId: input.fromTableId,
      toTableId: input.toTableId,
      outcome: 'moved',
      tableIds: nextTableIds,
      tableLabels: nextTableLabels,
    };
  });
}

function readStringArray(doc: Record<string, unknown>, key: string): string[] {
  const value = doc[key];
  return Array.isArray(value) ? value.map((entry) => String(entry)) : [];
}

/**
 * 目標桌現在不能有別人在用。
 *
 * 看的不是桌位文件上的 `activeSessionId`，而是**真的有沒有一張沒結帳的單掛在那一桌**。
 * 兩者平常一致，但店員手開的單根本不會寫 `activeSessionId`（applyOrderIntent 只寫
 * 訂單文件），只看指標會把那種桌當成空桌，於是兩攤客人的菜併到同一張帳單上。
 *
 * 已結帳的單留在 `orders` 裡等 releaseTables 清（SPEC 第十三節〈自動清桌〉），
 * 所以這裡要濾掉狀態，不能看到有單就擋——不然打烊前每張桌都轉不過去。
 */
async function assertTargetIsFree(
  tx: Transaction,
  refs: ReturnType<typeof tenantRefs>,
  input: MoveOrderTableInput,
  orderId: string,
): Promise<void> {
  const snap = await tx.get(refs.orders.where('tableIds', 'array-contains', input.toTableId));
  const blocking = snap.docs.find(
    (doc) => doc.id !== orderId && OCCUPYING_STATUSES.has(String(doc.data()['status'] ?? '')),
  );
  if (blocking !== undefined) {
    throw new HttpsError('failed-precondition', '那一桌還有客人在用，要合成同一張單請用併桌');
  }
}

/**
 * 找出這張單掛在來源桌上的 session。
 *
 * 從訂單的 `sessionIds` 出發而不是查 sessions collection：一張單的 session 數量
 * 本來就很少（通常就一個），而且這樣不必為了轉桌多開一個索引。
 */
async function readOrderSessions(
  tx: Transaction,
  refs: ReturnType<typeof tenantRefs>,
  order: Record<string, unknown>,
  fromTableId: string,
): Promise<SessionToMove[]> {
  const ids = readStringArray(order, 'sessionIds');
  if (ids.length === 0) return [];

  const snaps = await Promise.all(ids.map((id) => tx.get(refs.session(id))));
  return snaps
    .filter((snap) => snap.exists && snap.data()?.['tableId'] === fromTableId)
    .map((snap) => ({ id: snap.id, active: snap.data()?.['status'] === 'active' }));
}
