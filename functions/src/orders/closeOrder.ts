/**
 * 結帳（SPEC 第五節 `closeOrder`、第十三節〈結帳是硬分界線〉）。
 *
 * 結帳是這套系統裡唯一不可逆的動作。跨過這條線之後：總額鎖住、`businessDate` 寫進去、
 * 單搬到 `orders_archive`、客人那一攤結束。SPEC 第十三節講得很直白——**已結的單一律不可變更**，
 * 所以退點、轉桌、併單那幾支都把 `closed` 擋在外面。
 *
 * 做的六件事，順序在 transaction 裡是「全部讀完再全部寫」（Firestore 的硬限制）：
 *
 * 1. 鎖總額：用單上現有的數字，**不重算**。重算會拿當下的服務費設定去套一頓已經吃完的飯，
 *    老闆中途改了費率的話，客人結帳時看到的金額會跟他點餐時被告知的不一樣。
 * 2. 寫 `businessDate`：營業到凌晨兩點的單算前一天（SPEC 第三節第三條）。
 * 3. 產生 `receipts`：一個 session 一份，內容相同。併桌的兩桌客人各自掃自己的 QR
 *    都查得到同一張帳單（SPEC 第十三節第 4 點）。
 * 4. 搬進 `orders_archive` 並從 `orders` 刪掉：`orders` 要永遠只有數十筆，
 *    否則平板每次重連的初始快照會越來越貴（SPEC 第十一節第 2 點）。
 * 5. 關 session、清掉桌位的 `activeSessionId`：桌位回到「已結帳」，滿 3 小時由
 *    releaseTables 釋放成空桌（SPEC 第六節〈狀態轉換規則〉）。
 * 6. 順手刪掉該桌顧客的限流文件（SPEC 第九節）。
 *
 * 與其他幾支一樣，刻意只吃 `Firestore` 與已經驗過的輸入，不碰 onCall 的 request 物件。
 */

import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import type { DocumentSnapshot, Firestore } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { randomInt } from 'node:crypto';
import type { StaffCaller } from '../auth/staffAuth.js';
import { businessDateOf } from './businessDate.js';
import { readAmount, tenantRefs } from './orderDocs.js';
import { readBusinessSettings } from './settings.js';
import type { CloseOrderInput } from './closeOrderInput.js';

/**
 * 可以結帳的狀態，只有 `open`。
 *
 * `pending_confirm` 刻意不收：那是顧客自助送出、還沒有人按過確認的單，廚房根本沒收到。
 * 讓它直接結帳等於讓「確認」這道關卡可以被跳過，而那道關卡是 SPEC 第十二節階段 4 的
 * 驗收條件。店員的處置很明確——先按確認再結帳，多一下而已。
 */
const CLOSEABLE_STATUS = 'open';

/** 收據與已結帳 session 的可讀期，SPEC 第十三節。 */
const READABLE_HOURS = 3;

export type CloseOutcome =
  /** 真的結了 */
  | 'closed'
  /** 同一個 requestId 已經處理過，這次什麼都沒改 */
  | 'already_applied';

export interface CloseOrderResult {
  orderId: string;
  outcome: CloseOutcome;
  businessDate: string;
  /** 4 碼數字，客人 localStorage 掉了的時候拿來查帳單（SPEC 第十三節）。 */
  lookupCode: string;
  total: number;
  /** 付現才有；其他付款方式是 0。 */
  change: number;
}

export async function closeOrder(
  db: Firestore,
  input: CloseOrderInput,
  caller: StaffCaller,
  now: Date,
): Promise<CloseOrderResult> {
  const refs = tenantRefs(db, caller.storeId);

  // 換日時間讀在 transaction 外面，理由同其他幾支：它是老闆手動改才會變的設定。
  const business = readBusinessSettings((await refs.businessSettings.get()).data());
  const nowTs = Timestamp.fromDate(now);
  const expiresTs = Timestamp.fromDate(new Date(now.getTime() + READABLE_HOURS * 3600 * 1000));

  return db.runTransaction(async (tx): Promise<CloseOrderResult> => {
    const orderRef = refs.order(input.orderId);
    const archiveRef = refs.archivedOrder(input.orderId);
    const [orderSnap, archiveSnap] = await tx.getAll(orderRef, archiveRef);
    if (orderSnap === undefined || archiveSnap === undefined) {
      // getAll 回傳的筆數一定與要求的一樣多，這是 noUncheckedIndexedAccess 要的退路。
      throw new HttpsError('internal', '讀取訂單失敗，請再試一次');
    }

    // 重送要先看 archive。結帳成功之後這張單已經不在 `orders` 了，
    // 只看 `orders` 的話重送會得到「找不到這張單」，店員會以為沒結成功而再結一次。
    const replay = replayResult(archiveSnap, input.requestId);
    if (replay !== null) return replay;
    if (archiveSnap.exists) {
      throw new HttpsError('failed-precondition', '這張單已經結帳了');
    }

    if (!orderSnap.exists) {
      throw new HttpsError('not-found', '找不到這張單，請重新整理訂單列表');
    }
    const order = orderSnap.data() ?? {};

    assertCloseable(String(order['status'] ?? ''));

    const total = readAmount(order, 'total');
    const change = changeFor(input, total);

    const sessionIds = readStringArray(order, 'sessionIds');
    const tableIds = readStringArray(order, 'tableIds');
    // session 與桌位全部先讀完再寫：Firestore 不允許 transaction 裡讀在寫之後。
    const sessionSnaps =
      sessionIds.length === 0 ? [] : await tx.getAll(...sessionIds.map((id) => refs.session(id)));
    const tableSnaps =
      tableIds.length === 0 ? [] : await tx.getAll(...tableIds.map((id) => refs.table(id)));

    const businessDate = businessDateOf(now, business);
    const lookupCode = generateLookupCode();

    tx.set(archiveRef, {
      ...order,
      status: 'closed',
      businessDate,
      lookupCode,
      payment: {
        method: input.payment.method,
        ...(input.payment.received === undefined ? {} : { received: input.payment.received }),
        change,
      },
      closedAt: nowTs,
      closedBy: caller.uid,
      updatedAt: nowTs,
      appliedRequestIds: FieldValue.arrayUnion(input.requestId),
    });
    tx.delete(orderRef);

    for (const snap of sessionSnaps) {
      if (!snap.exists) continue;
      // 收據一個 session 一份，內容相同：併桌的兩桌客人各自掃自己的 QR 都查得到同一張帳單。
      // 文件 id 就是 sessionId，而 sessionId 是客人手機裡那把鑰匙（SPEC 第十三節）。
      tx.set(refs.receipt(snap.id), {
        orderId: orderRef.id,
        lookupCode,
        // tableLabels 是 SPEC 寫的顯示用欄位；tableIds 是 lookupReceipt 之後要用
        // 「這張 QR 對應的桌」去找收據的依據，光有顯示用的桌號名稱查不回來。
        tableLabel: readStringArray(order, 'tableLabels').join('、'),
        tableIds,
        lines: order['lines'] ?? [],
        subtotal: readAmount(order, 'subtotal'),
        serviceCharge: readAmount(order, 'serviceCharge'),
        discount: readAmount(order, 'discount'),
        total,
        taxSummary: order['taxSummary'] ?? null,
        paidAt: nowTs,
        expiresAt: expiresTs,
      });
      tx.update(snap.ref, { status: 'closed', closedAt: nowTs, readableUntil: expiresTs });
    }

    for (const snap of tableSnaps) {
      if (!snap.exists) continue;
      // 只有指標指著「這張單的 session」時才清。同一張桌可以同時有一個已結帳的 session
      // 和一個新客人的 active session（SPEC 第六節〈必須允許的並存狀態〉），
      // 無條件清掉會把剛坐下那一組人的桌位變成空桌。
      const activeSessionId = snap.data()?.['activeSessionId'];
      if (typeof activeSessionId === 'string' && sessionIds.includes(activeSessionId)) {
        tx.set(snap.ref, { activeSessionId: null }, { merge: true });
      }
    }

    // SPEC 第九節：結帳時順手刪掉該桌顧客的限流文件。只刪得掉開單那個人的——
    // 同桌其他人用自己的手機下單時，uid 沒有記在單上。這是清垃圾，不是安全機制，
    // 少刪幾份只是多留幾份過期文件。
    const createdBy = order['createdBy'];
    if (order['source'] === 'guest' && typeof createdBy === 'string' && createdBy.length > 0) {
      tx.delete(refs.rateLimit(createdBy));
      tx.delete(refs.rateLimit(`${createdBy}_read`));
    }

    return {
      orderId: orderRef.id,
      outcome: 'closed',
      businessDate,
      lookupCode,
      total,
      change,
    };
  });
}

/**
 * 重送：archive 裡那張單的 `appliedRequestIds` 有這個 requestId，就把當初的結果原樣回傳。
 *
 * 回的是 archive 上記著的數字，不是重算的——重送本來就不該改變任何東西。
 */
function replayResult(archive: DocumentSnapshot, requestId: string): CloseOrderResult | null {
  if (!archive.exists) return null;
  const data = archive.data() ?? {};
  const applied = data['appliedRequestIds'];
  if (!Array.isArray(applied) || !applied.map((id) => String(id)).includes(requestId)) return null;

  const payment = (data['payment'] ?? {}) as Record<string, unknown>;
  return {
    orderId: archive.id,
    outcome: 'already_applied',
    businessDate: String(data['businessDate'] ?? ''),
    lookupCode: String(data['lookupCode'] ?? ''),
    total: readAmount(data, 'total'),
    change: typeof payment['change'] === 'number' ? payment['change'] : 0,
  };
}

function assertCloseable(status: string): void {
  if (status === CLOSEABLE_STATUS) return;
  throw new HttpsError(
    'failed-precondition',
    status === 'pending_confirm'
      ? '這張單還沒確認，請先確認再結帳'
      : status === 'merged'
        ? '這張單已經併進別張單，請結那一張'
        : status === 'voided'
          ? '這張單已經作廢，不用結帳'
          : '這張單現在的狀態不能結帳，請重新整理訂單列表',
  );
}

/**
 * 找零只有付現算得出來，而且只由伺服器算：客人拿出來的錢減掉伺服器自己算的總額。
 *
 * 收的錢不夠就擋下來。這不是防弊，是防手滑——按錯一位數的結果是帳目對不起來，
 * 而現金的差額事後查不回去。
 */
function changeFor(input: CloseOrderInput, total: number): number {
  const received = input.payment.received;
  if (input.payment.method !== 'cash' || received === undefined) return 0;
  if (received < total) {
    throw new HttpsError('invalid-argument', `收到的金額不足，這張單是 ${total} 元`);
  }
  return received - total;
}

/**
 * 4 碼查詢碼（SPEC 第十三節）。
 *
 * 不檢查碰撞：`lookupReceipt` 是拿「這張桌的 QR」加上這 4 碼去查，而一張桌在 3 小時的
 * 可讀期內通常只有一兩份收據，兩份剛好撞號的機率可以忽略。改成全店唯一要在 transaction 裡
 * 多掃一次收據，換到的只是這個。
 */
function generateLookupCode(): string {
  return String(randomInt(0, 10_000)).padStart(4, '0');
}

function readStringArray(doc: Record<string, unknown>, key: string): string[] {
  const value = doc[key];
  return Array.isArray(value) ? value.map((entry) => String(entry)) : [];
}
