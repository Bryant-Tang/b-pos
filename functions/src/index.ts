import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { applyOrderIntent } from './orders/applyOrderIntent.js';
import { createGuestOrder } from './orders/createGuestOrder.js';
import { CreateOrderInput } from './orders/createOrderInput.js';
import { getTableState } from './orders/getTableState.js';
import { TableStateInput } from './orders/tableStateInput.js';

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
