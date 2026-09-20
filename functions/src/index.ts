import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { applyOrderIntent } from './orders/applyOrderIntent.js';

initializeApp();

const REGION = 'asia-east1';

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
  { document: 'tenants/{storeId}/order_intents/{intentId}', region: REGION },
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
