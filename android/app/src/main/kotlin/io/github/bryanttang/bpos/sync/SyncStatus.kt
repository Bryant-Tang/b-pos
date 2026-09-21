package io.github.bryanttang.bpos.sync

/**
 * 常駐顯示在畫面上的同步狀態（SPEC 第六節〈離線策略〉要求 UI 一直看得到）。
 *
 * 平板是 kiosk 模式整天不關機，店員不會去翻設定頁確認單有沒有送出去。
 * 「還有幾張單沒上去」必須一眼看得到，否則斷網一下午都不會有人發現。
 */
sealed interface SyncStatus {
    /** 佇列是空的，所有單都已經在伺服器上。 */
    data object UpToDate : SyncStatus

    /** 還有 [count] 張單沒送出去。 */
    data class Pending(val count: Int) : SyncStatus

    companion object {
        fun of(pendingCount: Int): SyncStatus =
            if (pendingCount <= 0) UpToDate else Pending(pendingCount)
    }
}
