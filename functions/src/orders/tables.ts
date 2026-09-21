/**
 * 用桌上印的 token 找桌位。
 *
 * 抽出來是因為兩支顧客端函式都要它：`createGuestOrder` 下單前要確認掃到的是哪一桌，
 * `getTableState` 進場時要拿桌號給客人看。兩邊必須是同一份判斷——一邊認得、另一邊
 * 認不得的 token，結果是客人看得到桌號卻送不出單。
 */

import type { Firestore } from 'firebase-admin/firestore';
import { tenantRefs } from './orderDocs.js';

export interface FoundTable {
  id: string;
  label: string;
}

/**
 * 只用 `qrToken` 一個條件查，`archived` 在程式裡判斷：兩個等式條件雖然 Firestore
 * 用單欄位索引就能跑，但這樣就得記得 `archived` 這個欄位永遠存在於每一份桌位文件上，
 * 少寫一次就是查不到桌位、客人掃了沒反應。
 */
export async function findTableByToken(
  db: Firestore,
  storeId: string,
  token: string,
): Promise<FoundTable | null> {
  const snap = await tenantRefs(db, storeId).tables.where('qrToken', '==', token).limit(1).get();
  const doc = snap.docs[0];
  if (!doc) return null;
  if (doc.data()['archived'] === true) return null;
  return { id: doc.id, label: String(doc.data()['label'] ?? '') };
}
