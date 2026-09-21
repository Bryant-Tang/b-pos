package io.github.bryanttang.bpos.sync

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.time.Duration

/**
 * 把意圖寫進 `tenants/{storeId}/order_intents/{intentId}`。
 *
 * 文件 id 就是 `intentId`，伺服器的 onDocumentCreated 觸發器接著查價、算價、建單
 * （`functions/src/orders/applyOrderIntent.ts`）。平板寫進去的內容裡沒有任何金額。
 */
class FirestoreOrderIntentSender(
    /**
     * 刻意收一個 provider 而不是 FirebaseFirestore 本身。
     *
     * `FirebaseFirestore.getInstance()` 在 Firebase 還沒設定好時會丟
     * IllegalStateException。在建構子就取的話，這個例外會發生在 worker 組裝階段、
     * 跑到 flush() 之前，於是整個 worker 直接失敗，佇列的 attempts 與退避
     * 完全沒機會生效。延後到 send() 裡面取，這個錯誤就會被 flush() 當成
     * 一次暫時性失敗記下來，照退避重試。
     */
    private val firestoreProvider: () -> FirebaseFirestore,
    private val storeId: String,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : OrderIntentSender {

    constructor(
        firestore: FirebaseFirestore,
        storeId: String,
        timeout: Duration = DEFAULT_TIMEOUT,
    ) : this({ firestore }, storeId, timeout)

    override suspend fun send(intent: OrderIntent): SendResult {
        val doc = firestoreProvider()
            .collection("tenants").document(storeId)
            .collection("order_intents").document(intent.intentId)

        return try {
            // Firestore 的 set() 有自己的離線佇列，斷網時這個 Task 會一直不完成
            // 直到連線回來為止。我們不能就這樣掛著等：佇列的節奏要由 RetryPolicy 決定，
            // 而且 WorkManager 的單次執行有時間上限，掛著等只會被系統砍掉。
            //
            // 逾時之後那筆寫入仍可能稍後才真的送達，這沒有關係——intentId 是冪等鍵，
            // 下次重試會踩到 AlreadyPresent，然後照樣標成已同步。
            withTimeout(timeout.toMillis()) {
                doc.set(intent.toFirestoreMap()).await()
            }
            SendResult.Accepted
        } catch (e: TimeoutCancellationException) {
            SendResult.Failed("送出逾時（${timeout.seconds} 秒）")
        } catch (e: FirebaseFirestoreException) {
            when (e.code) {
                // Rules 對 order_intents 是 create 放行、update 一律擋
                // （意圖送出後就是不可變的事實）。所以「文件已經存在」時的 set()
                // 會被判定成 update 而回 PERMISSION_DENIED——跟「這台平板沒有權限」
                // 是同一個錯誤碼。兩者的處置完全相反，必須查一下才知道是哪一種。
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    classifyPermissionDenied(doc, e)

                // 網路、逾時、伺服器忙，等一下再試。
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.ABORTED,
                FirebaseFirestoreException.Code.INTERNAL,
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
                -> SendResult.Failed(e.message)

                // 其餘（INVALID_ARGUMENT、UNAUTHENTICATED 等）重試也不會變，
                // 交給店員看。
                else -> SendResult.Rejected("${e.code}: ${e.message}")
            }
        }
    }

    private suspend fun classifyPermissionDenied(
        doc: com.google.firebase.firestore.DocumentReference,
        cause: FirebaseFirestoreException,
    ): SendResult = try {
        // 一定要問伺服器（Source.SERVER）。本地快取裡會有我們自己剛寫進去、
        // 還沒被確認的那份，拿它來判斷等於自己騙自己。
        val snapshot = withTimeout(timeout.toMillis()) {
            doc.get(Source.SERVER).await()
        }
        if (snapshot.exists()) {
            SendResult.AlreadyPresent
        } else {
            SendResult.Rejected("PERMISSION_DENIED: ${cause.message}")
        }
    } catch (e: TimeoutCancellationException) {
        // 查不出來是哪一種就先當成暫時性失敗。重試一筆已經存在的意圖是安全的
        // （會再踩到 AlreadyPresent），把還沒送出的單標成拒絕則會直接掉單。
        SendResult.Failed("確認意圖是否已存在時逾時")
    } catch (e: FirebaseFirestoreException) {
        SendResult.Failed("確認意圖是否已存在時失敗：${e.message}")
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
