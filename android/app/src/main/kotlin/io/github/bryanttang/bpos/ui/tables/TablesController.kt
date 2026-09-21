package io.github.bryanttang.bpos.ui.tables

import io.github.bryanttang.bpos.order.FirestorePendingOrders
import io.github.bryanttang.bpos.order.OpenOrder
import io.github.bryanttang.bpos.tables.FirestoreFloorPlan
import io.github.bryanttang.bpos.tables.FirestoreFloorStatus
import io.github.bryanttang.bpos.tables.Zone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock

/**
 * 桌位總覽現在該顯示什麼，外加待確認的顧客單。
 *
 * 待確認那一批放在這裡而不是待確認畫面自己收，是因為桌位總覽本來就要它：
 * 平面圖上哪張桌標成待確認、右上角的角標數字，用的都是同一批文件
 * （見 [FirestorePendingOrders]）。畫面自己再開一條就是同一份資料付兩次讀取。
 *
 * [loading] 只在「一份資料都還沒收到」時為 true。收到第一份之後就不再回 true，
 * 即使後來 listener 斷線重訂閱也一樣——那個空檔畫面上停著上一份快照
 * （見 `retryingSnapshots`），這時候轉圈圈只會讓店員以為資料被清掉了。
 */
data class TablesUiState(
    val zones: List<Zone> = emptyList(),
    val tables: List<TableOnPlan> = emptyList(),
    /** 還沒被店員確認的顧客自助單，先送出的排前面。 */
    val pendingOrders: List<OpenOrder> = emptyList(),
    val loading: Boolean = true,
)

/**
 * 把平面圖、場次、待確認單三條監聽合成一條給畫面用。
 *
 * 時間只在每次收到新資料時取一次。「已結帳滿三小時變回空桌」因此不會在畫面上
 * 自己跳——但伺服器的 `releaseTables` 排程每 15 分鐘會把過期的 session 刪掉，
 * 那是一次資料變動，畫面跟著就更新了。為了那十幾分鐘的誤差掛一個每秒重算的計時器，
 * 在一台整天不關機的平板上不划算。
 */
class TablesController(
    private val floorPlan: FirestoreFloorPlan,
    private val floorStatus: FirestoreFloorStatus,
    private val pendingOrders: FirestorePendingOrders,
    private val clock: Clock = Clock.systemUTC(),
) {

    val state: Flow<TablesUiState> =
        combine(
            floorPlan.observe(),
            floorStatus.observe(),
            pendingOrders.observe(),
        ) { plan, sessions, pending ->
            TablesUiState(
                zones = plan.zones,
                tables = tablesOnPlan(
                    tables = plan.tables,
                    sessions = sessions,
                    pendingConfirmOrderIds = pending.mapTo(mutableSetOf()) { it.orderId },
                    now = clock.instant(),
                ),
                pendingOrders = pending,
                loading = false,
            )
        }
}
