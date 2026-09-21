package io.github.bryanttang.bpos.firebase

import io.github.bryanttang.bpos.sync.RetryPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * 讓 Firestore 的 snapshot 監聽在出錯之後自己重新訂閱。
 *
 * `addSnapshotListener` 的錯誤是**終止性的**：一旦回了 error，那個 registration 就不會
 * 再送任何東西過來。包在 `callbackFlow` 裡的寫法是 `close(error)`，於是整條 Flow 也跟著死掉。
 *
 * 平板整天不關機，這件事一定會發生：店裡 Wi-Fi 抖一下、token 過期重取、老闆在後台
 * 動了一次 Rules，都會讓 listener 收到一個錯誤。沒有這一層的話，平面圖會從那一刻起
 * **永久空白**，而且畫面上不會有任何說明，店員唯一的解法是把 App 整個關掉重開。
 *
 * 退避沿用 [RetryPolicy]（2 秒起跳、上限 5 分鐘），理由跟離線佇列一樣：斷的通常是
 * 店裡的 Wi-Fi，幾分鐘內會好，退避到更久只會讓畫面在網路早就回來之後還空在那裡。
 *
 * **重訂閱的空檔不會送出任何東西**，所以收集端手上那份快照會原封不動留著。
 * 這是刻意的：網路抖一下就把平面圖清空、兩秒後再長回來，比畫面暫時停在舊資料糟得多
 * ——店員會以為桌位被刪了。真的需要讓店員知道「這份可能不是最新的」，
 * 是用 [onError] 回報給畫面，不是靠把資料清掉。
 *
 * @param onError 每次失敗時呼叫，帶著原因與「這是第幾次失敗」（從 1 起算）。
 *   預設不做事：重訂閱本身是背景行為，不是每個呼叫端都需要顯示它。
 */
fun <T> Flow<T>.retryingSnapshots(
    onError: (cause: Throwable, attempts: Int) -> Unit = { _, _ -> },
): Flow<T> = retryWhen { cause, attempt ->
    // attempt 是「先前已經重試過幾次」，從 0 起算；RetryPolicy 要的是「已經失敗過幾次」。
    // 夾住上限只是為了不讓 Long 轉 Int 溢位，實際值遠在那之前就到 MAX_DELAY 了。
    val attempts = (attempt + 1).coerceAtMost(MAX_COUNTED_ATTEMPTS).toInt()
    onError(cause, attempts)
    delay(RetryPolicy.delayAfter(attempts).toMillis())
    true
}

/** 超過這個次數之後退避時間不會再變，沒必要繼續往上數。 */
private const val MAX_COUNTED_ATTEMPTS = 31L
