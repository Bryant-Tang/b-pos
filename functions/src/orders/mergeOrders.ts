/**
 * 店員併單／併桌：把幾張還沒結帳的單合成一張（SPEC 第十三節〈分單與併單〉）。
 *
 * 實際的場景是併桌——兩桌客人本來分開坐，後來併在一起，希望一起結帳。SPEC 對這件事
 * 只提了一個硬要求：**併桌後兩張 QR 都指向同一張單**，客人無感。這裡的作法就是把
 * 來源單的 session 指到目標單，桌位文件上的 `activeSessionId` 完全不用動——
 * 客人手機裡存的 sessionId 沒變，掃哪一張 QR 都會走到同一張帳單。
 *
 * 來源單不刪。`firestore.rules` 對 `orders` 是 `allow delete: if false`（SPEC 第四節），
 * 而且對不上帳的時候，「這些品項是從哪張單併過來的」是查得出來才有意義。
 * 來源單改成 `merged` 並記下 `mergedInto`，同時把桌位交給目標單——桌位留著的話，
 * 平板的「這張桌的訂單」會同時列出目標單與一張空殼，店員看到兩張單卻只有一份菜。
 *
 * 與 voidOrderLine、moveOrderTable 一樣，刻意只吃 `Firestore` 與已經驗過的輸入，
 * 不碰 onCall 的 request 物件，這樣 emulator 測試可以直接呼叫。
 */

import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import type { DocumentSnapshot, Firestore } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import type { StaffCaller } from '../auth/staffAuth.js';
import { readAmount, readStoredLines, tenantRefs, toStoredLine } from './orderDocs.js';
import { readPricingSettings } from './settings.js';
import { calcOrderTotal, type OrderLine, type OrderType } from './pricing.js';
import type { MergeOrdersInput } from './mergeOrdersInput.js';

/**
 * 可以併的訂單狀態，目標單與來源單都適用。
 *
 * 與退點、轉桌是同一組，理由也一樣：結帳後的單已經寫了 `businessDate`、算進當日營收
 * （SPEC 第十三節〈結帳是硬分界線〉）。SPEC 第十三節第 3 點也明講「已結的單不能再拆」，
 * 併是同一件事的另一半。
 */
const MERGEABLE_STATUSES = new Set(['open', 'pending_confirm']);

export type MergeOutcome =
  /** 真的併了 */
  | 'merged'
  /** 同一個 requestId 已經處理過，這次什麼都沒改 */
  | 'already_applied';

export interface MergeOrdersResult {
  targetOrderId: string;
  mergedOrderIds: string[];
  outcome: MergeOutcome;
  tableIds: string[];
  tableLabels: string[];
  subtotal: number;
  serviceCharge: number;
  discount: number;
  total: number;
}

export async function mergeOrders(
  db: Firestore,
  input: MergeOrdersInput,
  caller: StaffCaller,
  now: Date,
): Promise<MergeOrdersResult> {
  const refs = tenantRefs(db, caller.storeId);

  // 服務費設定讀在 transaction 外面，理由同 voidOrderLine：它是老闆手動改才會變的東西。
  const pricing = readPricingSettings((await refs.pricingSettings.get()).data());
  const nowTs = Timestamp.fromDate(now);

  return db.runTransaction(async (tx): Promise<MergeOrdersResult> => {
    const targetRef = refs.order(input.targetOrderId);
    const targetSnap = await tx.get(targetRef);
    if (!targetSnap.exists) {
      throw new HttpsError('not-found', '找不到要留下來的那張單，請重新整理訂單列表');
    }
    const target = targetSnap.data() ?? {};

    // 冪等要在 transaction 內比對，理由同 voidOrderLine：兩次呼叫同時進來時，
    // 在外面比對會兩邊都讀到「還沒用過」。
    const appliedRequestIds = readStringArray(target, 'appliedRequestIds');
    if (appliedRequestIds.includes(input.requestId)) {
      return {
        targetOrderId: targetRef.id,
        mergedOrderIds: input.sourceOrderIds,
        outcome: 'already_applied',
        tableIds: readStringArray(target, 'tableIds'),
        tableLabels: readStringArray(target, 'tableLabels'),
        subtotal: readAmount(target, 'subtotal'),
        serviceCharge: readAmount(target, 'serviceCharge'),
        discount: readAmount(target, 'discount'),
        total: readAmount(target, 'total'),
      };
    }

    assertMergeable(target, '要留下來的那張單');
    const orderType = (target['orderType'] as OrderType | undefined) ?? 'dine_in';

    const sourceRefs = input.sourceOrderIds.map((id) => refs.order(id));
    const sourceSnaps = await tx.getAll(...sourceRefs);
    const sources = sourceSnaps.map((snap) => readSource(snap, orderType));

    // session 全部先讀完再寫。Firestore 的 transaction 不允許讀在寫之後，
    // 而且這裡要讀的是「來源單的 session」，一張單可能綁著不只一個。
    const sessionIdsToRepoint = sources.flatMap((source) => source.sessionIds);
    const sessionSnaps =
      sessionIdsToRepoint.length === 0
        ? []
        : await tx.getAll(...sessionIdsToRepoint.map((id) => refs.session(id)));

    const mergedLines: OrderLine[] = [
      ...readStoredLines(target),
      ...sources.flatMap((source) => source.lines),
    ];

    // 折扣相加。每一張單的折扣都是老闆當時對那張單的金額同意下去的，併單不該讓它消失——
    // 客人已經被告知折了多少。相加之後重算，服務費與總額才會對得上新的品項清單。
    //
    // 取整與取非負是因為這是從文件讀回來的數字：壞掉的一個欄位不該讓整支函式丟例外，
    // 那會變成「這兩桌永遠併不起來」。calcOrderTotal 自己也會把總額壓在 0 以上。
    const discount = Math.max(
      0,
      Math.round(readAmount(target, 'discount') + sources.reduce((sum, s) => sum + s.discount, 0)),
    );
    const totals = calcOrderTotal(mergedLines, orderType, pricing, discount);

    const tables = mergeTables(target, sources);
    const sessionIds = unique([
      ...readStringArray(target, 'sessionIds'),
      ...sources.flatMap((source) => source.sessionIds),
    ]);

    // 來源單的意圖與請求 id 一起接手：不接手的話，那些意圖重送時會被當成沒套用過，
    // 在目標單上再加一次同樣的品項。
    //
    // `arrayUnion()` 一個引數都不給會直接丟例外，所以空陣列時整個欄位不要寫——
    // 店員手開又沒被重送過的單，這兩個陣列本來就是空的。
    const inheritedIntentIds = unique(sources.flatMap((source) => source.appliedIntentIds));
    const inheritedRequestIds = unique(sources.flatMap((source) => source.appliedRequestIds));

    tx.update(targetRef, {
      lines: mergedLines.map(toStoredLine),
      tableIds: tables.ids,
      tableLabels: tables.labels,
      sessionIds,
      subtotal: totals.subtotal,
      serviceCharge: totals.serviceCharge,
      discount: totals.discount,
      total: totals.total,
      taxSummary: totals.taxSummary,
      ...(inheritedIntentIds.length > 0
        ? { appliedIntentIds: FieldValue.arrayUnion(...inheritedIntentIds) }
        : {}),
      appliedRequestIds: FieldValue.arrayUnion(input.requestId, ...inheritedRequestIds),
      mergedFrom: FieldValue.arrayUnion(...input.sourceOrderIds),
      updatedAt: nowTs,
      lastMergedBy: caller.uid,
      lastMergedAt: nowTs,
    });

    for (const source of sources) {
      tx.update(refs.order(source.id), {
        status: 'merged',
        mergedInto: targetRef.id,
        // 桌位交給目標單，但原本在哪一桌要留得住：這是之後對帳唯一查得到的地方。
        tableIdsBeforeMerge: source.tableIds,
        tableIds: [],
        tableLabels: [],
        updatedAt: nowTs,
        mergedAt: nowTs,
        mergedBy: caller.uid,
      });
    }

    for (const snap of sessionSnaps) {
      // 這就是 SPEC 第十三節第 4 點：把 B 桌的 session 指到 A 桌的 order，客人無感。
      // 桌位文件上的 activeSessionId 完全不動，所以兩張 QR 都還是掃得到自己的 session，
      // 只是它們現在指向同一張單。
      if (!snap.exists) continue;
      tx.update(snap.ref, { orderId: targetRef.id });
    }

    return {
      targetOrderId: targetRef.id,
      mergedOrderIds: input.sourceOrderIds,
      outcome: 'merged',
      tableIds: tables.ids,
      tableLabels: tables.labels,
      subtotal: totals.subtotal,
      serviceCharge: totals.serviceCharge,
      discount: totals.discount,
      total: totals.total,
    };
  });
}

interface SourceOrder {
  id: string;
  lines: OrderLine[];
  tableIds: string[];
  tableLabels: string[];
  sessionIds: string[];
  appliedIntentIds: string[];
  appliedRequestIds: string[];
  discount: number;
}

function readSource(snap: DocumentSnapshot, targetType: OrderType): SourceOrder {
  if (!snap.exists) {
    throw new HttpsError('not-found', '有一張要併進來的單找不到了，請重新整理訂單列表');
  }
  const data = snap.data() ?? {};
  assertMergeable(data, '要併進來的單');

  const sourceType = (data['orderType'] as OrderType | undefined) ?? 'dine_in';
  if (sourceType !== targetType) {
    // 內用與外帶的單價是兩個欄位（unitPriceDineIn / unitPriceTakeout），服務費也只有
    // 內用收。混在一起算出來的金額不會報錯，只會安靜地收錯錢。
    throw new HttpsError('failed-precondition', '內用單與外帶單不能併在一起');
  }

  return {
    id: snap.id,
    lines: readStoredLines(data),
    tableIds: readStringArray(data, 'tableIds'),
    tableLabels: readStringArray(data, 'tableLabels'),
    sessionIds: readStringArray(data, 'sessionIds'),
    appliedIntentIds: readStringArray(data, 'appliedIntentIds'),
    appliedRequestIds: readStringArray(data, 'appliedRequestIds'),
    discount: readAmount(data, 'discount'),
  };
}

function assertMergeable(order: Record<string, unknown>, which: string): void {
  const status = String(order['status'] ?? '');
  if (MERGEABLE_STATUSES.has(status)) return;
  throw new HttpsError(
    'failed-precondition',
    status === 'closed'
      ? `${which}已經結帳了，不能併`
      : status === 'merged'
        ? `${which}已經併進別張單了`
        : `${which}已經作廢，不能併`,
  );
}

/**
 * 桌位取聯集（SPEC 第五節的清單就是這麼寫的）。
 *
 * `tableLabels` 是與 `tableIds` 平行的顯示用快照，所以要一起搬，而且要對得上位置：
 * 錯位的結果是併完的單上寫著另一桌的桌號。舊的單可能沒有 labels 這個欄位或長度對不上
 * （欄位是後來加的），缺的補空字串而不是讓它錯位。
 */
function mergeTables(
  target: Record<string, unknown>,
  sources: SourceOrder[],
): { ids: string[]; labels: string[] } {
  const ids: string[] = [];
  const labels: string[] = [];

  const add = (tableIds: string[], tableLabels: string[]) => {
    tableIds.forEach((id, index) => {
      if (ids.includes(id)) return;
      ids.push(id);
      labels.push(tableLabels[index] ?? '');
    });
  };

  add(readStringArray(target, 'tableIds'), readStringArray(target, 'tableLabels'));
  for (const source of sources) add(source.tableIds, source.tableLabels);

  return { ids, labels };
}

function readStringArray(doc: Record<string, unknown>, key: string): string[] {
  const value = doc[key];
  return Array.isArray(value) ? value.map((entry) => String(entry)) : [];
}

function unique(values: string[]): string[] {
  return [...new Set(values)];
}
