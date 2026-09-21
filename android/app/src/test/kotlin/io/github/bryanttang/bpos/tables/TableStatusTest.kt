package io.github.bryanttang.bpos.tables

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TableStatusTest {

    private val now = Instant.parse("2026-01-15T12:00:00Z")

    private fun active(id: String = "session_1", pendingConfirm: Boolean = false) =
        TableSession(
            sessionId = id,
            isActive = true,
            readableUntil = null,
            hasPendingConfirmOrder = pendingConfirm,
        )

    private fun closed(id: String = "session_0", readableUntil: Instant) =
        TableSession(
            sessionId = id,
            isActive = false,
            readableUntil = readableUntil,
            hasPendingConfirmOrder = false,
        )

    // 沒有任何 session 就是空桌
    @Test
    fun `no sessions means the table is empty`() {
        assertEquals(TableStatus.EMPTY, deriveTableStatus(emptyList(), now))
    }

    // 有 active session 就是用餐中
    @Test
    fun `an active session means the table is occupied`() {
        assertEquals(TableStatus.OCCUPIED, deriveTableStatus(listOf(active()), now))
    }

    // active session 底下有未確認的顧客單就是待確認
    @Test
    fun `an unconfirmed guest order makes the table pending confirm`() {
        assertEquals(
            TableStatus.PENDING_CONFIRM,
            deriveTableStatus(listOf(active(pendingConfirm = true)), now),
        )
    }

    // 已結帳但還在可讀期，顯示已結帳
    @Test
    fun `a closed session inside its readable window shows as paid`() {
        val sessions = listOf(closed(readableUntil = now.plusSeconds(60)))
        assertEquals(TableStatus.PAID, deriveTableStatus(sessions, now))
    }

    // 可讀期過了就回到空桌
    @Test
    fun `a closed session past its readable window frees the table`() {
        val sessions = listOf(closed(readableUntil = now.minusSeconds(1)))
        assertEquals(TableStatus.EMPTY, deriveTableStatus(sessions, now))
    }

    /**
     * readableUntil 是「可讀到這個時刻為止」，剛好到點就算過期。
     * 這個邊界要跟伺服器的 releaseTables 排程一致，否則會出現
     * 平板顯示已結帳、伺服器那邊桌子已經釋放的矛盾。
     */
    @Test
    fun `the readable window ends exactly at readable until`() {
        val sessions = listOf(closed(readableUntil = now))
        assertEquals(TableStatus.EMPTY, deriveTableStatus(sessions, now))
    }

    /**
     * SPEC 明講的並存狀態：前一組客人在門口看帳單（已結帳、還可讀），
     * 新客人已經坐下點餐（active）。這時候桌子是「用餐中」，不是「已結帳」——
     * 顯示以 active session 為準，已結帳的只影響帳單可讀性。
     *
     * 這條寫錯的話，店員會看到一張「已結帳」的桌子上坐著正在吃飯的客人。
     */
    @Test
    fun `an active session wins over a still-readable closed one`() {
        val sessions = listOf(
            closed(id = "session_previous", readableUntil = now.plusSeconds(3600)),
            active(id = "session_current"),
        )
        assertEquals(TableStatus.OCCUPIED, deriveTableStatus(sessions, now))
    }

    // 並存時待確認一樣蓋過已結帳
    @Test
    fun `pending confirm also wins over a still-readable closed session`() {
        val sessions = listOf(
            closed(id = "session_previous", readableUntil = now.plusSeconds(3600)),
            active(id = "session_current", pendingConfirm = true),
        )
        assertEquals(TableStatus.PENDING_CONFIRM, deriveTableStatus(sessions, now))
    }

    /**
     * 同桌多單：一桌可以有多個 active session（併桌、同桌分開結）。
     * 只要其中任何一個有未確認的顧客單，整張桌就要標成待確認，
     * 否則那張單沒人會去按確認。
     */
    @Test
    fun `any active session with an unconfirmed order marks the whole table`() {
        val sessions = listOf(
            active(id = "session_a", pendingConfirm = false),
            active(id = "session_b", pendingConfirm = true),
        )
        assertEquals(TableStatus.PENDING_CONFIRM, deriveTableStatus(sessions, now))
    }

    /**
     * 多個已結帳 session，只要還有任何一個在可讀期內就顯示已結帳。
     */
    @Test
    fun `the table stays paid while any closed session is still readable`() {
        val sessions = listOf(
            closed(id = "session_old", readableUntil = now.minusSeconds(3600)),
            closed(id = "session_recent", readableUntil = now.plusSeconds(600)),
        )
        assertEquals(TableStatus.PAID, deriveTableStatus(sessions, now))
    }

    /**
     * 已結帳的 session 若沒有 readableUntil（理論上不該發生，但資料可能不完整），
     * 不能當成「永遠可讀」而把桌子卡住。
     */
    @Test
    fun `a closed session without a readable window does not hold the table`() {
        val sessions = listOf(
            TableSession(
                sessionId = "session_broken",
                isActive = false,
                readableUntil = null,
                hasPendingConfirmOrder = false,
            ),
        )
        assertEquals(TableStatus.EMPTY, deriveTableStatus(sessions, now))
    }
}
