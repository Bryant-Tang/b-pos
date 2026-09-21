import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { assertStaff } from './auth/staffAuth.js';
import { applyOrderIntent } from './orders/applyOrderIntent.js';
// 別名的理由同下面的 voidOrderLine。
import { confirmGuestOrder as applyConfirmGuestOrder } from './orders/confirmGuestOrder.js';
import { ConfirmGuestOrderInput } from './orders/confirmGuestOrderInput.js';
import { createGuestOrder } from './orders/createGuestOrder.js';
import { CreateOrderInput } from './orders/createOrderInput.js';
import { getTableState } from './orders/getTableState.js';
// 別名的理由與下面的 voidOrderLine 相同：匯出的變數名就是部署出去的函式名。
import { mergeOrders as applyMergeOrders } from './orders/mergeOrders.js';
import { MergeOrdersInput } from './orders/mergeOrdersInput.js';
// 同上。
import { moveOrderTable as applyMoveOrderTable } from './orders/moveOrderTable.js';
import { MoveOrderTableInput } from './orders/moveOrderTableInput.js';
import { TableStateInput } from './orders/tableStateInput.js';
// 別名是為了把 `voidOrderLine` 這個名字留給匯出的 callable：部署出去的函式名稱就是
// 匯出的變數名，而 SPEC 第五節的清單與平板呼叫的名字都是 voidOrderLine。
import { voidOrderLine as applyVoidOrderLine } from './orders/voidOrderLine.js';
import { VoidOrderLineInput } from './orders/voidOrderLineInput.js';

initializeApp();

const REGION = 'asia-east1';

/**
 * 同時最多跑幾個執行個體。
 *
 * 這是唯一一道「立刻生效」的成本防線。預算警示只會寄信，而 SPEC 第九節那套熔斷
 * 要等帳單資料，官方文件講明第一則通知可能要幾小時——一支不小心寫成自己觸發自己的
 * 函式，在那幾小時裡可以無限展開。上限壓住的就是這個展開。
 *
 * 取 10 的理由：單店一天約 200 單、每單不到一次觸發（SPEC 第九節的用量估算），
 * 正常營業連 1 個執行個體都用不滿。真正會同時湧進來的是平板離線後回線、
 * 一次把佇列裡的意圖全部送出，而每次執行是一個小 transaction（本機約數百毫秒），
 * 10 個併發足以在一兩秒內吃完幾十張單的佇列。
 *
 * 不設更低是因為超過上限的事件會排隊、久了會被丟掉，而這支沒有開 retry：
 * 掉一個事件就是一張單沒進系統。10 距離正常用量夠遠，不會誤擋。
 *
 * 顧客端的 createOrder 共用同一個上限。它的用量形狀更單純——一桌客人一次送出一張單，
 * 而且每個匿名 uid 每分鐘只放行五次（見 createGuestOrder.ts 的 GUEST_RATE_LIMIT）。
 */
const MAX_INSTANCES = 10;

/**
 * 店員端寫進 order_intents 之後，由伺服器查價、算價、建立或附加訂單。
 *
 * 沒有開 retry：所有「重試也不會成功」的失敗（意圖格式錯、品項不存在、單已結帳）
 * 都由 applyOrderIntent 寫回意圖文件的 rejectReason，平板看得到原因；
 * 剩下的才是暫時性失敗，而那種情況下意圖文件上 appliedAt 與 rejectedAt 都會是空的，
 * 平板的待同步清單本來就要靠這個判斷。開 retry 的話，一支會固定失敗的程式
 * 會對每一張單重試 24 小時，那是 SPEC 第九節最不想看到的帳單形狀。
 */
export const onOrderIntentCreated = onDocumentCreated(
  {
    document: 'tenants/{storeId}/order_intents/{intentId}',
    region: REGION,
    maxInstances: MAX_INSTANCES,
  },
  async (event) => {
    const data = event.data?.data();
    if (!data) return;
    const { storeId, intentId } = event.params;
    const outcome = await applyOrderIntent(getFirestore(), storeId, intentId, data, new Date());
    if (outcome.status === 'rejected') {
      console.warn(`意圖 ${intentId} 被拒絕：${outcome.reason} — ${outcome.detail}`);
    }
  },
);

/**
 * 顧客掃桌上的 QR code 自助點餐（SPEC 第五節〈createOrder〉）。
 *
 * 這裡只做三件事：擋掉沒有身分的呼叫、用 zod 驗輸入、把時間與 Firestore 交給
 * createGuestOrder。真正的邏輯在那邊，才能用 emulator 直接測。
 */
export const createOrder = onCall(
  {
    region: REGION,
    maxInstances: MAX_INSTANCES,
    /**
     * SPEC 第四節要求正式環境開 enforce（把「隨手寫腳本刷單」的成本抬高好幾個數量級）。
     * 但在應用程式還沒註冊到 App Check 之前開了，連從 console 手動打都會被擋，
     * 很容易誤判成程式壞掉（docs/firebase-setup.md〈App Check 測試階段不要開 enforce〉）。
     *
     * 刻意寫出來而不是靠預設值：上真機前要把這裡改成 true，寫出來才找得到。
     */
    enforceAppCheck: false,
  },
  async (req) => {
    // 顧客用的是匿名登入，SDK 在背景靜默取得，客人完全無感（SPEC 第十三節
    // 〈顧客端不得有登入介面〉）。會走到這裡通常是分頁開太久、token 過期。
    if (!req.auth) {
      throw new HttpsError('unauthenticated', '連線過期了，請重新整理頁面');
    }

    const parsed = CreateOrderInput.safeParse(req.data);
    if (!parsed.success) {
      // 詳細原因只留在伺服器日誌：回給客戶端的錯誤訊息是要給客人看的，
      // 而且逐欄回報等於免費告訴想試探的人 schema 長什麼樣。
      console.warn(
        `createOrder 輸入驗證失敗：${parsed.error.issues
          .map((i) => `${i.path.join('.')}: ${i.message}`)
          .join('; ')}`,
      );
      throw new HttpsError('invalid-argument', '訂單內容有誤，請重新整理頁面再試一次');
    }

    return createGuestOrder(getFirestore(), parsed.data, req.auth.uid, new Date());
  },
);

/**
 * 顧客掃到 QR code 的當下，先問這張桌現在什麼狀況（桌號、有沒有未結帳的單）。
 *
 * 只讀不寫。實際測出來的問題是：在這支之前，網頁在送出第一筆之前對這張桌一無所知，
 * 所以菜單頁上沒有桌號（掃錯桌要等點完才發現），而且同桌併單的既有品項會在送出後
 * 才突然冒出來。理由與回傳值的取捨寫在 orders/getTableState.ts。
 *
 * enforceAppCheck 與 createOrder 一樣先關著，上真機前一起開。
 */
export const tableState = onCall(
  { region: REGION, maxInstances: MAX_INSTANCES, enforceAppCheck: false },
  async (req) => {
    if (!req.auth) {
      throw new HttpsError('unauthenticated', '連線過期了，請重新整理頁面');
    }

    const parsed = TableStateInput.safeParse(req.data);
    if (!parsed.success) {
      console.warn(
        `tableState 輸入驗證失敗：${parsed.error.issues
          .map((i) => `${i.path.join('.')}: ${i.message}`)
          .join('; ')}`,
      );
      throw new HttpsError('invalid-argument', '這張 QR code 有問題，請洽服務人員');
    }

    return getTableState(getFirestore(), parsed.data, req.auth.uid, new Date());
  },
);

/**
 * 店員退點（SPEC 第五節〈voidOrderLine〉）。
 *
 * 這是第一支店員端的 callable，所以身分檢查那一層（auth/staffAuth.ts）也是從這裡開始的。
 * 要記得的一件事：**callable 完全繞過 firestore.rules**，rules 裡的 `isStaff()`
 * 對它一行都不生效，擋下越權的只有 assertStaff。
 *
 * 為什麼退點是 callable，而店員點餐卻是寫 order_intents：點餐要能離線（SPEC 第零節
 * 第三條），所以走 Firestore 的離線寫入再由觸發器補算；退點在 SPEC 的清單裡是 callable，
 * 而 callable 斷網就是直接失敗。也就是說**離線時退不了點**，平板要能講清楚這件事，
 * 不能讓店員按了沒反應。如果實際營業下這變成問題，就該把它也改成意圖文件那條路——
 * 那是資料流的改動，不是這一支的內部細節。
 *
 * enforceAppCheck 與 createOrder 同樣先關著，上真機前一起開（見上面的註解）。
 */
export const voidOrderLine = onCall(
  { region: REGION, maxInstances: MAX_INSTANCES, enforceAppCheck: false },
  async (req) => {
    const caller = assertStaff(req.auth);

    const parsed = VoidOrderLineInput.safeParse(req.data);
    if (!parsed.success) {
      console.warn(
        `voidOrderLine 輸入驗證失敗：${parsed.error.issues
          .map((i) => `${i.path.join('.')}: ${i.message}`)
          .join('; ')}`,
      );
      throw new HttpsError('invalid-argument', '退點的內容有誤，請重新整理訂單明細再試一次');
    }

    return applyVoidOrderLine(getFirestore(), parsed.data, caller, new Date());
  },
);

/**
 * 店員轉桌（SPEC 第九節〈訂單明細〉的「轉桌」）。
 *
 * 與 voidOrderLine 一樣是 callable，而且一樣**斷網就直接失敗**。這是刻意的：
 * 轉桌要同時改訂單、session 與兩張桌位文件，而且要擋掉「目標桌已經有人」——
 * SPEC 第二節〈邏輯該放哪〉把轉桌直接列成「必須在 Cloud Function」的跨使用者
 * 共享狀態，離線的平板手上沒有足夠的資訊可以自己決定。離線時店員請客人先坐著，
 * 回線再轉。
 *
 * enforceAppCheck 與其他幾支一樣先關著，上真機前一起開。
 */
export const moveOrderTable = onCall(
  { region: REGION, maxInstances: MAX_INSTANCES, enforceAppCheck: false },
  async (req) => {
    const caller = assertStaff(req.auth);

    const parsed = MoveOrderTableInput.safeParse(req.data);
    if (!parsed.success) {
      console.warn(
        `moveOrderTable 輸入驗證失敗：${parsed.error.issues
          .map((i) => `${i.path.join('.')}: ${i.message}`)
          .join('; ')}`,
      );
      throw new HttpsError('invalid-argument', '轉桌的內容有誤，請重新整理訂單明細再試一次');
    }

    return applyMoveOrderTable(getFirestore(), parsed.data, caller, new Date());
  },
);

/**
 * 店員併單／併桌（SPEC 第十三節〈分單與併單〉）。
 *
 * 與 moveOrderTable 同一個理由是 callable 而不是離線意圖：併單要同時改好幾張訂單與
 * 它們的 session，而且要重算金額——SPEC 第二節〈邏輯該放哪〉把併桌與任何涉及金額的
 * 計算都列成必須在 Cloud Function。離線時併不了單，平板要講清楚這件事。
 *
 * enforceAppCheck 與其他幾支一樣先關著，上真機前一起開。
 */
export const mergeOrders = onCall(
  { region: REGION, maxInstances: MAX_INSTANCES, enforceAppCheck: false },
  async (req) => {
    const caller = assertStaff(req.auth);

    const parsed = MergeOrdersInput.safeParse(req.data);
    if (!parsed.success) {
      console.warn(
        `mergeOrders 輸入驗證失敗：${parsed.error.issues
          .map((i) => `${i.path.join('.')}: ${i.message}`)
          .join('; ')}`,
      );
      throw new HttpsError('invalid-argument', '併單的內容有誤，請重新整理訂單列表再試一次');
    }

    return applyMergeOrders(getFirestore(), parsed.data, caller, new Date());
  },
);

/**
 * 店員確認顧客自助單（SPEC 第五節 `confirmGuestOrder`）。
 *
 * 是 callable 而不是離線意圖，理由與 moveOrderTable、mergeOrders 同一個：確認要以
 * 伺服器上那張單當下的狀態為準。離線的平板手上那份可能已經被別台確認、被結帳、
 * 甚至被併到別張單去了，照著舊快照補一次確認等於把已經處理完的單再放行一次。
 *
 * enforceAppCheck 與其他幾支一樣先關著，上真機前一起開。
 */
export const confirmGuestOrder = onCall(
  { region: REGION, maxInstances: MAX_INSTANCES, enforceAppCheck: false },
  async (req) => {
    const caller = assertStaff(req.auth);

    const parsed = ConfirmGuestOrderInput.safeParse(req.data);
    if (!parsed.success) {
      console.warn(
        `confirmGuestOrder 輸入驗證失敗：${parsed.error.issues
          .map((i) => `${i.path.join('.')}: ${i.message}`)
          .join('; ')}`,
      );
      throw new HttpsError('invalid-argument', '確認的內容有誤，請重新整理待確認列表再試一次');
    }

    return applyConfirmGuestOrder(getFirestore(), parsed.data, caller, new Date());
  },
);
