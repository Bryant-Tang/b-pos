package io.github.bryanttang.bpos.menu

/**
 * 去伺服器拿一次目前發佈中的菜單。
 *
 * 抽成介面的理由跟 [io.github.bryanttang.bpos.sync.OrderIntentSender] 一樣：
 * 讓「要不要換成新版、抓不到時怎麼辦」這段判斷在沒有 Firebase 專案的情況下測得起來，
 * 不是為了日後可能換掉 Firestore（那種預留依 SPEC 第二節是不做的）。
 */
interface MenuSource {
    suspend fun fetch(storeId: String): MenuFetch
}

sealed interface MenuFetch {

    /** 伺服器上有一份讀得懂的菜單。 */
    data class Published(val menu: Menu) : MenuFetch

    /**
     * 連得上，但伺服器上沒有可用的菜單。
     *
     * 兩種情況合在一起：老闆還沒按過「發佈」，或者文件在但連 `version` 都讀不出來。
     * 對店員而言兩者一樣（畫面上都是「這間店還沒有菜單」），差別只在後者是 bug，
     * 前者是還沒設定完。
     */
    data object NotPublished : MenuFetch

    /** 這次沒拿到（斷網、逾時、權限、伺服器有狀況）。手上那份快取還能用。 */
    data class Unavailable(val error: String?) : MenuFetch
}
