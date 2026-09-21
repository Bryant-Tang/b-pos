package io.github.bryanttang.bpos.remote

/**
 * 店員按下一個按鈕之後，伺服器回了什麼。
 *
 * 只有三種結局，因為店員當下只需要知道三件事：成了、要不要再按一次、還是別按了。
 */
sealed interface ActionResult {

    /** 成了。伺服器回的內容放在 [payload]，用不到就忽略。 */
    data class Done(val payload: Map<String, Any?>) : ActionResult

    /**
     * 這次沒成，但**再按一次是有意義的**：網路不通、伺服器忙、逾時。
     *
     * 重送安全，因為每個動作都帶著同一個 `requestId`（伺服器照那個去重），
     * 所以畫面可以放心把按鈕放回去讓店員再按。
     */
    data class Retryable(val message: String) : ActionResult

    /**
     * 這次沒成，而且**再按幾次都一樣**：那張單已經被別台處理掉了、狀態不對、權限不足。
     *
     * 畫面要把原因寫出來並叫店員重新整理，不要讓他對著同一個按鈕按到放棄。
     */
    data class Refused(val message: String) : ActionResult
}
