package io.github.bryanttang.bpos.sync

/*
 * 測試方法名一律用 ASCII，理由見 RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusTest {

    // 佇列清空時顯示已同步
    @Test
    fun `empty queue reads as up to date`() {
        assertEquals(SyncStatus.UpToDate, SyncStatus.of(0))
    }

    // 有待送的單時顯示筆數
    @Test
    fun `pending entries are reported with their count`() {
        assertEquals(SyncStatus.Pending(3), SyncStatus.of(3))
    }

    /**
     * COUNT(*) 不會回負數，但這個轉換是 UI 的最後一道：
     * 真的收到負數時顯示「待同步 -1 筆」比顯示「已同步」更糟。
     */
    @Test
    fun `negative count is treated as up to date`() {
        assertEquals(SyncStatus.UpToDate, SyncStatus.of(-1))
    }
}
