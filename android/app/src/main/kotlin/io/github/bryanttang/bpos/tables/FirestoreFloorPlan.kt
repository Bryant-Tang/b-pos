package io.github.bryanttang.bpos.tables

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine

/** 平面圖：區域分頁與它們底下的桌子。 */
data class FloorPlan(
    val zones: List<Zone>,
    val tables: List<Table>,
) {
    fun tablesIn(zoneId: String): List<Table> =
        tables.filter { it.zoneId == zoneId }.sortedBy { it.sort }
}

/**
 * 訂閱店裡的平面圖。
 *
 * 桌位與區域是老闆在後台改的，一天可能一次也沒有。但老闆就是會在營業中把一張桌
 * 拖到別的位置（SPEC 第三節：平板也能拖，因為人就在現場），這時候店裡其他平板
 * 要跟著變，不然兩台平板指著不同的桌子是同一張桌。
 *
 * 用 listener 的代價很小：桌子只有幾十份文件，而且只在變動時才算讀取。真正會燒讀取
 * 的是訂單那種一直在變的集合，SPEC 第六節〈監聽器範圍〉講的是那個。
 */
class FirestoreFloorPlan(
    /** 同其他 Firestore 類別：延後取實例，Firebase 還沒設定好時才不會在組裝階段就炸掉。 */
    private val firestoreProvider: () -> FirebaseFirestore,
    private val storeId: String,
) {

    fun observe(): Flow<FloorPlan> =
        combine(observeZones(), observeTables()) { zones, tables -> FloorPlan(zones, tables) }

    private fun observeZones(): Flow<List<Zone>> =
        collection("areas") { snapshot ->
            snapshot.mapNotNull { toZone(it.id, it.data.orEmpty()) }.sortedBy { it.sort }
        }

    private fun observeTables(): Flow<List<Table>> =
        collection("tables") { snapshot ->
            snapshot.mapNotNull { toTable(it.id, it.data.orEmpty()) }.sortedBy { it.sort }
        }

    private fun <T> collection(
        name: String,
        map: (List<com.google.firebase.firestore.DocumentSnapshot>) -> List<T>,
    ): Flow<List<T>> = callbackFlow {
        val registration = firestoreProvider()
            .collection("tenants").document(storeId)
            .collection(name)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(map(snapshot?.documents.orEmpty()))
            }

        // 每個 listener 都要有對應的 remove（SPEC 第六節）。殘留的監聽器在平板
        // 整天不關機的情況下會一直算讀取。
        awaitClose { registration.remove() }
    }
}
