package io.github.bryanttang.bpos.ui

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import io.github.bryanttang.bpos.data.local.BposDatabase
import io.github.bryanttang.bpos.menu.FirestoreMenuSource
import io.github.bryanttang.bpos.menu.MenuRepository
import io.github.bryanttang.bpos.order.FirestorePendingOrders
import io.github.bryanttang.bpos.order.FirestoreTableOrders
import io.github.bryanttang.bpos.order.OrderSubmitter
import io.github.bryanttang.bpos.remote.StaffFunctions
import io.github.bryanttang.bpos.sync.FirestoreOrderIntentSender
import io.github.bryanttang.bpos.sync.OutboxRepository
import io.github.bryanttang.bpos.tables.FirestoreFloorPlan
import io.github.bryanttang.bpos.tables.FirestoreFloorStatus
import io.github.bryanttang.bpos.ui.checkout.CheckoutController
import io.github.bryanttang.bpos.ui.pending.PendingConfirmController
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
    /**
     * 待確認畫面的狀態機。
     *
     * 放在這裡而不是畫面裡 `remember` 出來，是因為它記著每張單的冪等鍵，而
     * BposApp 是用 `when (stack.current)` 直接換分支的——畫面一離開，那棵子樹連同
     * `remember` 的東西一起被丟掉。放在畫面裡的話，店員「按了確認、退回總覽、再進來」
     * 就會拿到一個全新的鍵，[PendingConfirmController] 要擋的那件事正好就不生效。
     */
    val pendingConfirm: PendingConfirmController,
    /** 結帳的狀態機。放在這裡的理由同 [pendingConfirm]，而且結帳更需要：見 [CheckoutController]。 */
    val checkout: CheckoutController,
)

fun appServices(context: Context, storeId: String): AppServices {
    val firestore = { FirebaseFirestore.getInstance() }
    val database = BposDatabase.get(context)
    // 待確認與結帳共用這一個；之後的退點、轉桌、併桌也是。
    val staffFunctions = StaffFunctions()

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
        pendingConfirm = PendingConfirmController(
            sendConfirm = staffFunctions::confirmGuestOrder,
        ),
        checkout = CheckoutController(sendClose = staffFunctions::closeOrder),
    )
}
