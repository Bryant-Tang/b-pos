package io.github.bryanttang.bpos.remote

import com.google.firebase.functions.FirebaseFunctionsException.Code

/**
 * 把 callable 的錯誤碼分成「再按一次有用」與「再按也沒用」。
 *
 * 分這兩類而不是照抄十幾個錯誤碼，是因為店員當下只會做兩件事：再按一次，
 * 或是去查發生什麼事。把 `UNAVAILABLE` 跟 `NOT_FOUND` 用同一種方式呈現的話，
 * 前者會被放棄（其實再按就好），後者會被按到放棄（其實永遠不會成）。
 *
 * **重送一律是安全的**：每個動作都帶著同一個 `requestId`，伺服器照那個去重
 * （CLAUDE.md 第二節第三條）。所以這裡可以放心把整類暫時性錯誤標成可重試。
 */
fun actionResultFor(code: Code, serverMessage: String?): ActionResult = when (code) {
    // 網路不通、函式冷啟太久、Firestore transaction 撞在一起重試用完。
    // 這些都是「現在不行，等一下可能就行」。
    Code.UNAVAILABLE,
    Code.DEADLINE_EXCEEDED,
    Code.ABORTED,
    Code.RESOURCE_EXHAUSTED,
    Code.INTERNAL,
    Code.CANCELLED,
    Code.UNKNOWN,
    -> ActionResult.Retryable(serverMessage.orElse(TRANSIENT))

    // 這幾種是狀態或權限的問題，再按一百次也一樣。伺服器那邊的訊息是寫給店員看的
    // （例如「找不到這張單，請重新整理待確認列表」），有就直接用。
    else -> ActionResult.Refused(serverMessage.orElse(PERMANENT))
}

/**
 * 連 callable 都沒送出去（沒網路、DNS 不通、TLS 失敗）。
 *
 * 這一類一律算可重試：請求根本沒到伺服器，重送不可能造成第二次副作用。
 */
fun networkFailureResult(): ActionResult = ActionResult.Retryable(TRANSIENT)

private fun String?.orElse(fallback: String): String =
    this?.takeIf { it.isNotBlank() } ?: fallback

private const val TRANSIENT = "連不到伺服器，請確認平板的 Wi-Fi 之後再按一次"
private const val PERMANENT = "伺服器拒絕了這個動作，請重新整理之後再看一次"
