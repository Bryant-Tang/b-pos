package io.github.bryanttang.bpos.tables

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import io.github.bryanttang.bpos.firebase.retryingSnapshots
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine

/**
 * 訂閱桌位顏色狀態要用的兩份資料：場次與還沒確認的顧客單。
 *
 * 兩個 listener 的範圍都是刻意壓小的（SPEC 第六節〈監聽器範圍〉）：
 *
 * - 訂單只聽 `pending_confirm`。SPEC 第六節的範例是 `whereIn` 兩個狀態，
 *   這裡再窄一點：顏色要的「有沒有人在用」是從場次來的，訂單只用來回答
 *   「那張單確認過了沒有」，所以 `open` 的單一筆都不需要聽。少聽一種狀態，
 *   平板整天不關機省下來的讀取不是小數目。
 * - 場次沒有辦法再縮：已結帳但還在可讀期的場次也要算進來（那張桌要顯示成
 *   「已結帳」），而可讀期一過 TTL 政策就會把文件刪掉，所以這個集合本來就不大。
 */
class FirestoreFloorStatus(
    private val firestoreProvider: () -> FirebaseFirestore,
    private val storeId: String,
) {

    /** 場次與「還沒確認的單」的 id，合起來就是推導顏色要的全部。 */
    data class Snapshot(
        val sessions: List<LiveSession>,
        val pendingConfirmOrderIds: Set<String>,
    )

    fun observe(): Flow<Snapshot> =
        combine(observeSessions(), observePendingConfirmOrderIds()) { sessions, pending ->
            Snapshot(sessions, pending)
        }

    private fun observeSessions(): Flow<List<LiveSession>> =
        listen({ it.collection("sessions") }) { docs ->
            docs.mapNotNull { toLiveSession(it.id, it.data.orEmpty()) }
        }

    private fun observePendingConfirmOrderIds(): Flow<Set<String>> =
        listen({
            it.collection("orders").whereEqualTo("status", "pending_confirm")
        }) { docs ->
            docs.map { doc -> doc.id }.toSet()
        }

    /** 出錯就重新訂閱（見 [retryingSnapshots]）；兩個 listener 各自重試，互不影響。 */
    private fun <T> listen(
        query: (DocumentReference) -> Query,
        map: (List<DocumentSnapshot>) -> T,
    ): Flow<T> = subscribe(query, map).retryingSnapshots()

    private fun <T> subscribe(
        query: (DocumentReference) -> Query,
        map: (List<DocumentSnapshot>) -> T,
    ): Flow<T> = callbackFlow {
        val tenant = firestoreProvider().collection("tenants").document(storeId)
        val registration = query(tenant).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(map(snapshot?.documents.orEmpty()))
        }

        // 每個 listener 都要有對應的 remove（SPEC 第六節）。
        awaitClose { registration.remove() }
    }
}
