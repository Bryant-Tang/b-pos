package io.github.bryanttang.bpos.ui.order

/* 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。 */

import io.github.bryanttang.bpos.order.OrderStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderStatusLabelTest {

    /**
     * 每個狀態都要有自己的字串。
     *
     * 這條擋的是複製貼上：`CLOSED -> R.string.order_status_open` 編得過、
     * 畫面也不會壞，只是一張已經結完帳的單顯示成「用餐中」，然後被收第二次錢。
     */
    @Test
    fun `every status maps to its own label`() {
        val ids = OrderStatus.entries.map { it.labelRes() }

        assertEquals(OrderStatus.entries.size, ids.distinct().size)
        assertEquals(0, ids.count { it == 0 })
    }
}
