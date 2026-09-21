package io.github.bryanttang.bpos.tables

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 *
 * 資料全部是虛構的（CLAUDE.md 第一條）。
 */

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.Date

class FloorStatusTest {

    private val now = Instant.parse("2026-09-20T12:00:00Z")
    private val stillReadable = now.plusSeconds(3600)
    private val expired = now.minusSeconds(1)

    private fun session(
        id: String,
        tableId: String,
        isActive: Boolean = true,
        readableUntil: Instant? = null,
        orderId: String? = "order_$id",
    ) = LiveSession(id, tableId, isActive, readableUntil, orderId)

    private fun statuses(
        tableIds: List<String> = listOf("t1"),
        sessions: List<LiveSession> = emptyList(),
        pending: Set<String> = emptySet(),
    ) = tableStatuses(tableIds, sessions, pending, now)

    /**
     * 沒有任何 session 的桌子也要在結果裡。
     *
     * 少了這一步，一張從來沒人坐過的桌子會從結果裡消失，平面圖上就破一個洞，
     * 店員以為那張桌被刪了。
     */
    @Test
    fun `a table nobody has ever sat at is still in the result`() {
        assertEquals(mapOf("t1" to TableStatus.EMPTY), statuses())
    }

    @Test
    fun `every table asked for comes back`() {
        val result = statuses(tableIds = listOf("t1", "t2", "t3"), sessions = listOf(session("s1", "t1")))

        assertEquals(setOf("t1", "t2", "t3"), result.keys)
        assertEquals(TableStatus.OCCUPIED, result["t1"])
        assertEquals(TableStatus.EMPTY, result["t2"])
    }

    @Test
    fun `an active session makes the table occupied`() {
        assertEquals(TableStatus.OCCUPIED, statuses(sessions = listOf(session("s1", "t1")))["t1"])
    }

    @Test
    fun `an unconfirmed guest order shows up as pending confirm`() {
        val result = statuses(
            sessions = listOf(session("s1", "t1", orderId = "order_a")),
            pending = setOf("order_a"),
        )

        assertEquals(TableStatus.PENDING_CONFIRM, result["t1"])
    }

    // 別張桌的待確認單不該染到這一桌。
    @Test
    fun `another table's unconfirmed order does not colour this one`() {
        val result = statuses(
            tableIds = listOf("t1", "t2"),
            sessions = listOf(session("s1", "t1", orderId = "order_a"), session("s2", "t2", orderId = "order_b")),
            pending = setOf("order_b"),
        )

        assertEquals(TableStatus.OCCUPIED, result["t1"])
        assertEquals(TableStatus.PENDING_CONFIRM, result["t2"])
    }

    @Test
    fun `a session without an order id is never pending confirm`() {
        val result = statuses(
            sessions = listOf(session("s1", "t1", orderId = null)),
            pending = setOf("order_a"),
        )

        assertEquals(TableStatus.OCCUPIED, result["t1"])
    }

    @Test
    fun `a closed session still in its readable window shows as paid`() {
        val result = statuses(
            sessions = listOf(session("s1", "t1", isActive = false, readableUntil = stillReadable)),
        )

        assertEquals(TableStatus.PAID, result["t1"])
    }

    @Test
    fun `a closed session past its readable window frees the table`() {
        val result = statuses(
            sessions = listOf(session("s1", "t1", isActive = false, readableUntil = expired)),
        )

        assertEquals(TableStatus.EMPTY, result["t1"])
    }

    /**
     * SPEC 第六節〈必須允許的並存狀態〉：前一組客人在門口看帳單、新客人已經坐下。
     * 桌位顯示以 active session 為準。
     */
    @Test
    fun `a table with both a paid and an active session is occupied`() {
        val result = statuses(
            sessions = listOf(
                session("s_old", "t1", isActive = false, readableUntil = stillReadable, orderId = "order_old"),
                session("s_new", "t1", orderId = "order_new"),
            ),
        )

        assertEquals(TableStatus.OCCUPIED, result["t1"])
    }

    @Test
    fun `no tables means no statuses`() {
        assertEquals(emptyMap<String, TableStatus>(), statuses(tableIds = emptyList()))
    }

    // session 指到一張已經被刪掉的桌子時，不要憑空長出一張桌來。
    @Test
    fun `a session pointing at a table we do not have is ignored`() {
        assertEquals(mapOf("t1" to TableStatus.EMPTY), statuses(sessions = listOf(session("s1", "t_gone"))))
    }
}

class SessionMappingTest {

    private val readableUntil = Instant.parse("2026-09-20T15:00:00Z")

    private fun doc(vararg over: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "tableId" to "table_1",
        "status" to "active",
        "orderId" to "order_1",
        "openedAt" to Timestamp(Date(0)),
        "closedAt" to null,
        "readableUntil" to null,
    ) + over

    @Test
    fun `an active session maps across`() {
        val session = toLiveSession("sess_1", doc())

        assertEquals("sess_1", session?.sessionId)
        assertEquals("table_1", session?.tableId)
        assertEquals(true, session?.isActive)
        assertEquals("order_1", session?.orderId)
        assertNull(session?.readableUntil)
    }

    @Test
    fun `a closed session carries its readable window`() {
        val session = toLiveSession(
            "sess_1",
            doc("status" to "closed", "readableUntil" to Timestamp(Date(readableUntil.toEpochMilli()))),
        )

        assertEquals(false, session?.isActive)
        assertEquals(readableUntil, session?.readableUntil)
    }

    /**
     * 狀態欄位壞掉時當成不在用。
     *
     * 猜成在用的話，那張桌會一直是「用餐中」，誰都坐不進去，而且沒有任何操作解得開
     * ——只能等人去後台改資料。
     */
    @Test
    fun `a broken status is not treated as active`() {
        for (status in listOf(null, "", "ACTIVE", 1L, "opened")) {
            assertEquals(false, toLiveSession("sess_1", doc("status" to status))?.isActive)
        }
    }

    @Test
    fun `a session without a table or id is skipped`() {
        assertNull(toLiveSession("sess_1", doc("tableId" to null)))
        assertNull(toLiveSession("sess_1", doc("tableId" to "")))
        assertNull(toLiveSession("", doc()))
    }

    @Test
    fun `a blank order id is treated as none`() {
        assertNull(toLiveSession("sess_1", doc("orderId" to ""))?.orderId)
        assertNull(toLiveSession("sess_1", doc("orderId" to 7L))?.orderId)
    }
}
