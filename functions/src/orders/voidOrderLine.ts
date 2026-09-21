/**
 * 店員退點（SPEC 第五節〈voidOrderLine〉）。
 *
 * 整支函式的重點是一條分界：**這一行送進廚房了沒有。**
 *
 * - 還沒送（`printedAt` 是 null）→ 從 lines 裡真的移除。那通常是點錯了、還沒出單，
 *   留一行劃掉的紀錄只會讓訂單明細愈滑愈長，店員反而看不清楚客人到底點了什麼。
 * - 已經送了（`printedAt` 非 null）→ 只標 `voidedAt` 與 `voidReason`，那一行留著。
 *   菜已經在做或已經做好了，從帳上抹掉就是白送一份，而且稅法要求作廢留痕
 *   （SPEC 第四節，也是 `allow delete: if false` 的同一個理由）。
 *
 * 這一層只負責資料。作廢單要不要印、什麼時候印，是列印佇列那一段的事，
 * 所以回傳值帶一個 `needsVoidTicket` 讓呼叫端知道有沒有這件事要做。
 *
 * 與 createGuestOrder 一樣，刻意只吃 `Firestore` 與已經驗過的輸入，不碰 onCall 的
 * request 物件，這樣 emulator 測試可以直接呼叫。
 */

import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import type { Firestore } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import type { StaffCaller } from '../auth/staffAuth.js';
import { readAmount, readStoredLines, tenantRefs, toStoredLine } from './orderDocs.js';
import { readPricingSettings } from './settings.js';
import { calcOrderTotal, type OrderLine, type OrderType } from './pricing.js';
import type { VoidOrderLineInput } from './voidOrderLineInput.js';

/**
 * 可以退點的訂單狀態。
 *
 * 與加點那一邊（APPENDABLE_STATUSES）是同一組，理由也一樣：已結帳與已作廢的單不能再動。
 * 結帳後才發現要退，走的是作廢重開或差額單（SPEC 第十五節），不是回來改這張單——
 * 那張單已經開過發票了。
 */
const VOIDABLE_STATUSES = new Set(['open', 'pending_confirm']);

export type VoidOutcome =
  /** 沒送廚房，整行移除 */
  | 'removed'
  /** 已送廚房，標記作廢並留痕 */
  | 'voided'
  /** 同一個 requestId 已經處理過，這次什麼都沒改 */
  | 'already_applied'
  /** 這一行本來就已經是作廢狀態（別人先退過了），這次什麼都沒改 */
  | 'already_voided';

export interface VoidOrderLineResult {
  orderId: string;
  lineId: string;
  outcome: VoidOutcome;
  /** 這次退點是否該印一張作廢單給廚房（SPEC 第八節）。 */
  needsVoidTicket: boolean;
  subtotal: number;
  serviceCharge: number;
  discount: number;
  total: number;
}

function result(
  orderId: string,
  lineId: string,
  outcome: VoidOutcome,
  needsVoidTicket: boolean,
  totals: { subtotal: number; serviceCharge: number; discount: number; total: number },
): VoidOrderLineResult {
  return { orderId, lineId, outcome, needsVoidTicket, ...totals };
}

export async function voidOrderLine(
  db: Firestore,
  input: VoidOrderLineInput,
  caller: StaffCaller,
  now: Date,
): Promise<VoidOrderLineResult> {
  const refs = tenantRefs(db, caller.storeId);

  // 服務費設定讀在 transaction 外面，理由同 createGuestOrder：它是老闆手動改才會變的東西。
  const pricing = readPricingSettings((await refs.pricingSettings.get()).data());
  const nowTs = Timestamp.fromDate(now);

  return db.runTransaction(async (tx): Promise<VoidOrderLineResult> => {
    const orderRef = refs.order(input.orderId);
    const snap = await tx.get(orderRef);
    if (!snap.exists) {
      throw new HttpsError('not-found', '找不到這張單，請重新整理訂單列表');
    }
    const order = snap.data() ?? {};

    const orderType = (order['orderType'] as OrderType | undefined) ?? 'dine_in';
    const discount = readAmount(order, 'discount');
    const lines = readStoredLines(order);
    const current = {
      subtotal: readAmount(order, 'subtotal'),
      serviceCharge: readAmount(order, 'serviceCharge'),
      discount,
      total: readAmount(order, 'total'),
    };

    // 冪等要在 transaction 內比對：兩次呼叫同時進來時，在外面比對會兩邊都讀到「還沒用過」。
    const appliedRequestIds = (order['appliedRequestIds'] as string[] | undefined) ?? [];
    if (appliedRequestIds.includes(input.requestId)) {
      // needsVoidTicket 是 false：作廢單在第一次成功的那一次就已經交給列印佇列了，
      // 重送一次不該再印一張——廚房收到兩張同樣的作廢單會以為退了兩份。
      return result(orderRef.id, input.lineId, 'already_applied', false, current);
    }

    const status = String(order['status'] ?? '');
    if (!VOIDABLE_STATUSES.has(status)) {
      throw new HttpsError(
        'failed-precondition',
        status === 'closed'
          ? '這張單已經結帳了，要調整金額請走作廢重開'
          : '這張單已經作廢，不能再退點',
      );
    }

    const target = lines.find((line) => line.lineId === input.lineId);
    if (target === undefined) {
      throw new HttpsError('not-found', '找不到這一筆，請重新整理訂單明細');
    }
    if (target.voidedAt !== null) {
      // 兩個店員同時退同一行。後到的那個不該看到錯誤——他想要的結果已經成立了。
      return result(orderRef.id, input.lineId, 'already_voided', false, current);
    }

    const printed = target.printedAt !== null;
    const nextLines: OrderLine[] = printed
      ? lines.map((line) =>
          line.lineId === input.lineId
            ? // subtotal 一起歸零。算總額的 lineSubtotal 本來就會把作廢的行當成 0，
              // 但文件裡存著的那個數字是另一回事：平板的訂單明細、結帳快照、日報表
              // 讀的都是這個欄位，不同步的話畫面上會出現一行「已作廢」卻還標著 60 元。
              { ...line, subtotal: 0, voidedAt: now, voidReason: input.reason ?? null }
            : line,
        )
      : lines.filter((line) => line.lineId !== input.lineId);

    // 用訂單自己的 orderType 重算，而且只重算總額不重查菜單：退一行不該讓同一張單上
    // 其他品項的價格跟著變成今天的菜單價（SPEC 第十五節）。
    const totals = calcOrderTotal(nextLines, orderType, pricing, discount);

    tx.update(orderRef, {
      lines: nextLines.map(toStoredLine),
      subtotal: totals.subtotal,
      serviceCharge: totals.serviceCharge,
      total: totals.total,
      taxSummary: totals.taxSummary,
      appliedRequestIds: FieldValue.arrayUnion(input.requestId),
      updatedAt: nowTs,
      // 誰退的要留痕：退點是唯一一個店員可以讓帳面金額變少的操作（折扣是老闆限定），
      // 對不上帳的時候第一個要問的就是這個。
      lastVoidedBy: caller.uid,
      lastVoidedAt: nowTs,
    });

    return result(
      orderRef.id,
      input.lineId,
      printed ? 'voided' : 'removed',
      printed,
      totals,
    );
  });
}
