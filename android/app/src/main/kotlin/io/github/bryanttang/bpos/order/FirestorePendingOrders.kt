package io.github.bryanttang.bpos.order

import com.google.firebase.firestore.FirebaseFirestore
import io.github.bryanttang.bpos.firebase.retryingSnapshots
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 訂閱還沒被店員確認的顧客自助單（SPEC 第六節〈畫面〉的「待確認」那一列）。
 *
 * **全 App 只有這一條在聽 `pending_confirm`。** 桌位總覽右上角的角標、平面圖上
 * 哪張桌要標成待確認、待確認列表本身，三個地方要的是同一批文件，各開一條 listener
 * 就等於同一份資料付三次讀取。所以這條收在 [io.github.bryanttang.bpos.ui.tables.TablesController]
 * 裡跟平面圖、場次合在一起，登入後一直開著，待確認畫面直接用那份結果
 * （SPEC 第六節〈監聽器範圍〉）。
 */
class FirestorePendingOrders(
    /** 同 FirestoreTableOrders：延後取實例，Firebase 還沒設定好時才不會在組裝階段就炸掉。 */
    private val firestoreProvider: () -> FirebaseFirestore,
    private val storeId: String,
) {

    /** 待確認的單，先送出的排前面——客人等的順序就是店員該處理的順序。 */
    fun observe(): Flow<List<OpenOrder>> = subscribe().retryingSnapshots()

    /** [observe] 的裸監聽，錯誤會終止它；外面那一層負責重訂閱（見 [retryingSnapshots]）。 */
    private fun subscribe(): Flow<List<OpenOrder>> = callbackFlow {
        val registration = firestoreProvider()
            .collection("tenants").document(storeId)
            .collection("orders")
            .whereEqualTo("status", "pending_confirm")
            .limit(MAX_ORDERS)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val orders = snapshot?.documents.orEmpty()
                    .map { toOpenOrder(it.id, it.data.orEmpty()) }
                    .sortedWith(compareBy(nullsLast()) { it.createdAt })
                trySend(orders)
            }

        awaitClose { registration.remove() }
    }

    private companion object {
        /**
         * 待確認的單正常是個位數——客人一送出，店員幾秒內就按掉了。
         * 設上限是為了萬一哪裡出錯灌了一堆進來時，平板不會整台卡住。
         *
         * 排序是拿到之後在平板上做的，不是 `orderBy`。查詢只有一個等值條件，
         * 用的是自動建好的單欄位索引；加上 `orderBy("createdAt")` 就要多一個
         * 複合索引，而索引沒先部署上去時這個查詢是直接失敗的，待確認畫面會整頁壞掉。
         * 真的多到超過上限時被截掉的那幾張是隨機的——但那已經是「哪裡出錯了」的情況，
         * 那時候順序不是重點。
         */
        const val MAX_ORDERS = 50L
    }
}
