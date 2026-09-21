package io.github.bryanttang.bpos.auth

/**
 * 這台平板現在是誰在用。
 *
 * [SignedIn.storeId] 來自 custom claim，不是使用者填的，也不是寫死在 App 裡的。
 * 它決定所有 Firestore 路徑的第一段（`tenants/{storeId}/…`），所以一旦弄錯，
 * 送出去的單會落在別家店的資料夾底下、然後被 Rules 擋掉。
 */
sealed interface StaffSession {

    data object SignedOut : StaffSession

    data class SignedIn(
        val uid: String,
        val email: String,
        val storeId: String,
        val role: StaffRole,
    ) : StaffSession
}
