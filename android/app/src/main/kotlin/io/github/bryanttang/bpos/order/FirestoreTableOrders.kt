package io.github.bryanttang.bpos.order

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import io.github.bryanttang.bpos.firebase.retryingSnapshots
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 訂閱一張桌現在的訂單。
 *
 * 用即時監聽而不是拉一次就好：同一張桌可能有另一台平板在點餐，客人自己也可能
 * 從手機加點（SPEC 第五節）。店員看著明細的時候後面多了一筆，畫面要自己長出來，
 * 不能等他退出去再進來。
 *
 * 監聽也順便解決了斷網：Firestore 的本地快取會先回上次看到的內容，回線後補上差異。
 * 平板對 `orders` 只有讀取權限，所以這條路上不會有任何寫入。
 */
class FirestoreTableOrders(
    /** 同 FirestoreOrderIntentSender：延後取實例，Firebase 還沒設定好時才不會在組裝階段就炸掉。 */
    private val firestoreProvider: () -> FirebaseFirestore,
    private val storeId: String,
) {

    /**
     * 這張桌的訂單，新的在前面。
     *
     * 只讀 `orders`，不含 `orders_archive`：結帳會把單搬進 archive（SPEC 第五節
     * 〈closeOrder〉），所以這裡回的就是「還在進行中的單」。SPEC 第六節〈同桌多單〉
     * 要的「本桌今日 N 張單（含已結帳的）」還要再讀 archive，那要等 closeOrder 做出來
     * 才有東西可讀。
     */
    fun observe(tableId: String): Flow<List<OpenOrder>> = subscribe(tableId).retryingSnapshots()

    /** [observe] 的裸監聽，錯誤會終止它；外面那一層負責重訂閱（見 [retryingSnapshots]）。 */
    private fun subscribe(tableId: String): Flow<List<OpenOrder>> = callbackFlow {
        val registration = firestoreProvider()
            .collection("tenants").document(storeId)
            .collection("orders")
            .whereArrayContains("tableIds", tableId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(MAX_ORDERS)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val orders = snapshot?.documents.orEmpty().map { toOpenOrder(it.id, it.data.orEmpty()) }
                trySend(orders)
            }

        awaitClose { registration.remove() }
    }

    private companion object {
        /**
         * 一張桌同時進行中的單通常只有一張，併桌與結帳後加點才會多。
         * 設上限是為了萬一哪裡出錯灌了一堆單進來時，平板不會整台卡住。
         */
        const val MAX_ORDERS = 20L
    }
}
