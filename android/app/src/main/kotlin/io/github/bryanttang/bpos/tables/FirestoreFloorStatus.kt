package io.github.bryanttang.bpos.tables

import com.google.firebase.firestore.FirebaseFirestore
import io.github.bryanttang.bpos.firebase.retryingSnapshots
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 訂閱現場的場次（`tenants/{storeId}/sessions`）。
 *
 * 範圍沒有辦法再縮（SPEC 第六節〈監聽器範圍〉）：已結帳但還在可讀期的場次也要算進來，
 * 那張桌要顯示成「已結帳」；而可讀期一過 TTL 政策就會把文件刪掉，所以這個集合本來就不大。
 *
 * 顏色要的另外一半——「那張單確認過了沒有」——來自
 * [io.github.bryanttang.bpos.order.FirestorePendingOrders]，兩條在
 * [io.github.bryanttang.bpos.ui.tables.TablesController] 合起來。那條本來就要開給
 * 待確認畫面用，顏色跟著用同一條，比在這裡再開一條一樣的查詢少付一半讀取。
 */
class FirestoreFloorStatus(
    private val firestoreProvider: () -> FirebaseFirestore,
    private val storeId: String,
) {

    /** 出錯就重新訂閱（見 [retryingSnapshots]）。 */
    fun observe(): Flow<List<LiveSession>> = subscribe().retryingSnapshots()

    private fun subscribe(): Flow<List<LiveSession>> = callbackFlow {
        val registration = firestoreProvider()
            .collection("tenants").document(storeId)
            .collection("sessions")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(
                    snapshot?.documents.orEmpty().mapNotNull { toLiveSession(it.id, it.data.orEmpty()) },
                )
            }

        // 每個 listener 都要有對應的 remove（SPEC 第六節）。
        awaitClose { registration.remove() }
    }
}
