package io.github.bryanttang.bpos.sync

import java.time.Duration

/**
 * 送不出去的意圖要等多久再試。
 *
 * 指數退避，2 秒起跳，上限 5 分鐘：
 * 2s → 4s → 8s → 16s → 32s → 64s → 128s → 256s → 300s → 300s …
 *
 * **沒有加隨機抖動（jitter）是刻意的。** 抖動是為了避免成千上萬個客戶端同時重試
 * 打垮伺服器，而這裡是一間店的一台平板，沒有這個問題。不加抖動換來的是
 * 「同樣的輸入永遠得到同樣的輸出」，測得準，出事時也推得出下一次會在什麼時候試。
 *
 * **上限是 5 分鐘而不是更久**，因為斷的通常是店裡的 Wi-Fi，幾分鐘內會好。
 * 退避到半小時的話，網路早就回來了，單還躺在佇列裡沒送出去。
 */
object RetryPolicy {
    val INITIAL_DELAY: Duration = Duration.ofSeconds(2)
    val MAX_DELAY: Duration = Duration.ofMinutes(5)

    /**
     * 第 [attempts] 次嘗試失敗之後，要等多久再試。
     *
     * [attempts] 是「已經失敗過幾次」，所以第一次失敗傳 1，得到 2 秒。
     */
    fun delayAfter(attempts: Int): Duration {
        require(attempts >= 1) { "attempts 從 1 起算，收到 $attempts" }
        // 1 << 30 就已經遠超上限，再往上會整數溢位變成負數，所以先夾住指數。
        val exponent = (attempts - 1).coerceAtMost(30)
        val seconds = INITIAL_DELAY.seconds shl exponent
        return if (seconds >= MAX_DELAY.seconds) MAX_DELAY else Duration.ofSeconds(seconds)
    }
}
