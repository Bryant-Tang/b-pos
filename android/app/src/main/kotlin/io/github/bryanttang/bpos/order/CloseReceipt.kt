package io.github.bryanttang.bpos.order

/**
 * 結帳成功之後伺服器回的那份結果（closeOrder.ts 的 `CloseOrderResult`）。
 *
 * **三個數字全部是伺服器的，平板一個都不算**（CLAUDE.md 第二節第一條）。
 * [change] 尤其是：那是店員要從抽屜拿出去的錢。
 */
data class CloseReceipt(
    /** 伺服器結帳當下鎖住的總額。可能跟畫面上看到的不同——另一台平板剛好退了一份的話。 */
    val total: Int,
    /** 找零。付現以外是 0。 */
    val change: Int,
    /** 4 碼查詢碼，客人手機上的帳單不見時拿來查（SPEC 第十三節）。 */
    val lookupCode: String,
    /**
     * 這是一次重送拿回來的舊結果（伺服器的 `already_applied`）：上一次其實結成了，
     * 只是回應在路上掉了。數字是當時那一次的，不是這次輸入的。
     */
    val replayed: Boolean,
) {
    companion object {
        /**
         * 讀伺服器的回應。總額或找零讀不出來就回 null，**不退回 0**。
         *
         * 這跟讀訂單（OpenOrderMapping）的取捨剛好相反：那邊一個欄位壞掉退回 0，
         * 只是那一格顯示錯；這裡的找零退回 0，店員會真的少找客人錢。
         * 讀不懂的時候寧可講「讀不懂」，讓店員去看紙本或後台。
         */
        fun from(payload: Map<String, Any?>): CloseReceipt? {
            val total = (payload["total"] as? Number)?.toInt() ?: return null
            val change = (payload["change"] as? Number)?.toInt() ?: return null
            return CloseReceipt(
                total = total,
                change = change,
                lookupCode = payload["lookupCode"] as? String ?: "",
                replayed = payload["outcome"] == "already_applied",
            )
        }
    }
}
