package io.github.bryanttang.bpos.sync

/**
 * 把一筆意圖送到伺服器。
 *
 * 抽成介面是為了讓佇列的重試與冪等邏輯能在沒有 Firebase 專案的情況下測得起來，
 * 不是為了「日後可能換掉 Firestore」——那種預留依 SPEC 第二節是不做的。
 */
interface OrderIntentSender {
    suspend fun send(intent: OrderIntent): SendResult
}

sealed interface SendResult {
    /** 伺服器收下了。 */
    data object Accepted : SendResult

    /**
     * 伺服器上**已經有**這筆意圖了。
     *
     * 這不是錯誤，是冪等機制正常運作：上一次其實送成功了，只是回應沒回到平板
     * （網路在中間斷掉、App 被砍掉）。既然 `intentId` 一樣，伺服器上就只有一份，
     * 當成成功處理。
     */
    data object AlreadyPresent : SendResult

    /**
     * 伺服器拒絕，而且再送幾次都一樣（意圖內容不合法、權限不足）。
     * 不重試，要讓店員看到。
     */
    data class Rejected(val reason: String) : SendResult

    /** 這次沒送到（斷網、逾時、伺服器暫時有問題）。等一下再試。 */
    data class Failed(val error: String?) : SendResult
}
