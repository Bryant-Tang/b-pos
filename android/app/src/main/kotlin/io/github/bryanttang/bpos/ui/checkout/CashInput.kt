package io.github.bryanttang.bpos.ui.checkout

/**
 * 店員在「收到多少現金」那一格打了什麼。
 *
 * 這裡**只比大小，不算錢**：找零是伺服器算的（closeOrder.ts 的 changeFor），
 * 伺服器也會自己再擋一次收不夠的情況。在平板先比一次只是為了按錯一位數的時候
 * 不用等一趟網路才知道——那一趟在尖峰時段是好幾秒，而客人就站在櫃台前。
 */
sealed interface CashInput {
    /** 還沒打。 */
    data object Empty : CashInput

    /** 收的錢比總額少。[total] 帶著，畫面要講出「這張單是幾元」。 */
    data class TooLittle(val total: Int) : CashInput

    /** 可以送出。 */
    data class Ok(val received: Int) : CashInput
}

/**
 * [text] 是輸入框的內容。輸入框本身只收數字（見 [digitsOnly]），所以這裡不處理
 * 「打了字母」這種情況；空字串與超出範圍一律當成還沒打完。
 */
fun parseCashInput(text: String, total: Int): CashInput {
    val received = text.toIntOrNull() ?: return CashInput.Empty
    return if (received < total) CashInput.TooLittle(total) else CashInput.Ok(received)
}

/**
 * 輸入框的過濾：只留數字，最多 [MAX_DIGITS] 位。
 *
 * 位數上限只是讓這一格不會長到畫面裝不下，也讓 [String.toIntOrNull] 永遠讀得出來。
 * 它擋不住「多打一個零」——那種手滑要靠結帳完成後畫面上把總額、收到、找零三個數字
 * 並排列出來，店員在把錢拿出抽屜之前看得到。
 */
fun digitsOnly(text: String): String = text.filter { it.isDigit() }.take(MAX_DIGITS)

private const val MAX_DIGITS = 6
