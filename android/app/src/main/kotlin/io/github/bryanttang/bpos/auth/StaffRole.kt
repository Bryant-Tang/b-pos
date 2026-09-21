package io.github.bryanttang.bpos.auth

/**
 * 登入後由 `setStaffRole` Function 寫進 custom claims 的角色（SPEC 第五節）。
 *
 * 平板上兩種角色能做的事目前一樣多——老闆本來就能做店員能做的一切。
 * 之所以還是把它讀出來而不是一律當 staff，是因為改價、改菜單這類只有老闆能做的
 * 動作日後會出現在平板上（SPEC 第八節），到時候要判斷的就是這個值。
 */
enum class StaffRole {
    STAFF,
    OWNER,
    ;

    companion object {
        /**
         * claim 裡的字串轉成角色。不認得的值一律回 null 當成「沒有角色」，
         * 不要猜成 STAFF：claim 是權限的來源，猜錯的方向是把權限放大。
         */
        fun parse(raw: String?): StaffRole? = when (raw) {
            "staff" -> STAFF
            "owner" -> OWNER
            else -> null
        }
    }
}
