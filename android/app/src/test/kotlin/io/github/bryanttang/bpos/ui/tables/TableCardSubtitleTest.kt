// 測試方法名一律用 ASCII（可以有空白、包在反引號裡），中文說明寫在註解。
// 原因見 sync/OutboxRepositoryTest.kt 開頭。
package io.github.bryanttang.bpos.ui.tables

import io.github.bryanttang.bpos.tables.Table
import io.github.bryanttang.bpos.tables.TableStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡片上桌名底下那一行的文字。
 *
 * 抽成純函式才測得到——包在 Composable 裡就要跑起 Compose 測試，
 * 而這裡要驗的只是「什麼時候該講單數」這條規則。
 */
class TableCardSubtitleTest {

    private fun onPlan(seats: Int, orderCount: Int) = TableOnPlan(
        table = Table(
            tableId = "table_a1",
            label = "A1",
            zoneId = "zone_1f",
            x = 0f,
            y = 0f,
            sort = 0,
            seats = seats,
        ),
        status = TableStatus.OCCUPIED,
        orderCount = orderCount,
    )

    // 沒有單的空桌只講人數。
    @Test
    fun `a table with no orders shows only its seat count`() {
        assertEquals("4 人", cardSubtitle(onPlan(seats = 4, orderCount = 0)))
    }

    // 只有一張單時不講單數：每桌都掛一個「1 張單」等於沒有資訊。
    @Test
    fun `a single order is not called out`() {
        assertEquals("4 人", cardSubtitle(onPlan(seats = 4, orderCount = 1)))
    }

    // 超過一張才講，這是店員真的需要注意的事（同桌多單）。
    @Test
    fun `more than one order is called out next to the seat count`() {
        assertEquals("6 人・3 張單", cardSubtitle(onPlan(seats = 6, orderCount = 3)))
    }
}
