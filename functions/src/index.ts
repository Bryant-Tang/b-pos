import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { applyOrderIntent } from './orders/applyOrderIntent.js';

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
