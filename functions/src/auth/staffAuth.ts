/**
 * 店員端 callable 的身分檢查。
 *
 * **這一層是店員端唯一的權限閘門。** callable function 走的是 admin SDK，
 * 完全繞過 firestore.rules——`isStaff()` 那些規則對它一行都不生效。
 * 所以 rules 裡寫了什麼，這裡就得自己再寫一次，漏掉的那條就是真的沒有人在擋。
 *
 * 身分只從 ID token 的 custom claims 來（`storeId` 與 `role`，由 setStaffRole 寫入，
 * SPEC 第五節）。claims 是 Firebase 簽章過的，呼叫端改不了。
 */

import { HttpsError } from 'firebase-functions/v2/https';

export type StaffRole = 'staff' | 'owner';

export interface StaffCaller {
  uid: string;
  /**
   * 呼叫者所屬的店，**一律從 token 來，永遠不從請求輸入來。**
   *
   * 這是多租戶隔離唯一的支點。如果讓呼叫端在輸入裡帶 storeId，A 店的店員把它改成 B 店
   * 就能直接對 B 店的訂單動手——而且因為 callable 繞過 rules，沒有第二道會擋下來。
   * 所以店員端的輸入 schema 裡不會有 storeId 這個欄位（voidOrderLineInput.ts）。
   */
  storeId: string;
  role: StaffRole;
}

/** 與 firestore.rules 的 `isStaff()` 同一份定義：owner 是 staff 的超集。 */
function parseRole(raw: unknown): StaffRole | null {
  return raw === 'staff' || raw === 'owner' ? raw : null;
}

/** callable request 裡 `req.auth` 的形狀，只取這一層用得到的部分。 */
export interface CallerAuth {
  uid: string;
  token: Record<string, unknown>;
}

/**
 * 讀出呼叫者身分；不是有效的店員就回 null。
 *
 * 純函式，與 firebase-functions 的 request 物件無關，所以可以直接餵假 claims 測。
 */
export function readStaffCaller(auth: CallerAuth | undefined): StaffCaller | null {
  if (!auth) return null;
  const storeId = auth.token['storeId'];
  const role = parseRole(auth.token['role']);
  if (typeof storeId !== 'string' || storeId.length === 0 || role === null) return null;
  return { uid: auth.uid, storeId, role };
}

/**
 * 錯誤訊息刻意不分「沒登入」與「claims 不對」。
 *
 * 前者是 token 過期，後者是這個帳號還沒被 setStaffRole 設定過角色，兩種的處置
 * 都是同一件事：重新登入，還是不行就找老闆。分得更細只是讓平板上多一種看不懂的訊息。
 * （平板端分得比較細，因為那裡有畫面可以引導——見 android 的 SignInError。）
 */
export function assertStaff(auth: CallerAuth | undefined): StaffCaller {
  const caller = readStaffCaller(auth);
  if (caller === null) {
    throw new HttpsError('permission-denied', '這個帳號沒有店員權限，請重新登入');
  }
  return caller;
}
