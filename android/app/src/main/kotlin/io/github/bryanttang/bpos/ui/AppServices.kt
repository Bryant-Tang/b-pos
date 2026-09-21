package io.github.bryanttang.bpos.ui

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import io.github.bryanttang.bpos.data.local.BposDatabase
import io.github.bryanttang.bpos.menu.FirestoreMenuSource
import io.github.bryanttang.bpos.menu.MenuRepository
import io.github.bryanttang.bpos.order.FirestorePendingOrders
import io.github.bryanttang.bpos.order.FirestoreTableOrders
import io.github.bryanttang.bpos.order.OrderSubmitter
import io.github.bryanttang.bpos.sync.FirestoreOrderIntentSender
import io.github.bryanttang.bpos.sync.OutboxRepository
import io.github.bryanttang.bpos.tables.FirestoreFloorPlan
import io.github.bryanttang.bpos.tables.FirestoreFloorStatus
import io.github.bryanttang.bpos.ui.tables.TablesController

/**
 * 登入之後這家店會用到的東西，一次組好。
 *
 * **這不是一層架構，就是一個組裝函式。** 每一樣東西都要 `storeId`，而 storeId
 * 來自登入後的 custom claim（見 [io.github.bryanttang.bpos.auth.StaffSession]），
 * 所以它們最早也只能在這個時間點組出來，不可能寫成 Application 層的單例。
 * 換帳號（換店）時整包重組，不會有上一家店的監聽殘留著。
 *
 * Firestore 一律收 provider 延後取實例，理由見 [FirestoreOrderIntentSender] 的建構子註解。
 */
class AppServices(
    val tables: TablesController,
    val menuRepository: MenuRepository,
    val submitter: OrderSubmitter,
    val tableOrders: FirestoreTableOrders,
)

fun appServices(context: Context, storeId: String): AppServices {
    val firestore = { FirebaseFirestore.getInstance() }
    val database = BposDatabase.get(context)

    return AppServices(
        tables = TablesController(
            floorPlan = FirestoreFloorPlan(firestore, storeId),
            floorStatus = FirestoreFloorStatus(firestore, storeId),
            pendingOrders = FirestorePendingOrders(firestore, storeId),
        ),
        menuRepository = MenuRepository(
            dao = database.menuSnapshotDao(),
            source = FirestoreMenuSource(firestore),
        ),
        submitter = OrderSubmitter(
            OutboxRepository(
                dao = database.orderIntentOutboxDao(),
                sender = FirestoreOrderIntentSender(firestore, storeId),
            ),
        ),
        tableOrders = FirestoreTableOrders(firestore, storeId),
    )
}
