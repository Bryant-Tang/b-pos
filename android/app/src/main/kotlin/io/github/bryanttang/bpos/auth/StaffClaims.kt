package io.github.bryanttang.bpos.auth

/**
 * 從 ID token 的 custom claims 組出登入狀態。
 *
 * 帳號建得起來不代表能用：`setStaffRole` 還沒對這個 uid 跑過的話，
 * 帳號密碼是對的、Firebase 也會讓他登入，但 claims 是空的，
 * 這台平板連自己屬於哪家店都不知道。那不是「登入失敗」，
 * 是「登入成功但這個帳號還不能用」，訊息要分得開，
 * 否則老闆會一直以為是自己密碼打錯。
 */
fun readStaffSession(
    uid: String,
    email: String?,
    claims: Map<String, Any?>,
): SignInResult {
    // claims 是從 JSON 解出來的 Map<String, Any>，型別由伺服器那頭決定。
    // 這裡刻意只接受字串：storeId 被寫成數字時 toString() 出來的東西
    // 看起來像個合理的 id，實際上跟 Firestore 裡的文件 id 對不起來。
    val storeId = claims["storeId"] as? String
    val role = StaffRole.parse(claims["role"] as? String)

    if (storeId.isNullOrBlank() || role == null) {
        return SignInResult.Failure(SignInError.NOT_A_STAFF_ACCOUNT)
    }

    return SignInResult.Success(
        StaffSession.SignedIn(
            uid = uid,
            // email 在 Firebase 的型別是可為 null（匿名帳號就沒有）。
            // 店員帳號一定有，但這裡不假設，空字串只影響畫面上顯示誰登入了。
            email = email.orEmpty(),
            storeId = storeId,
            role = role,
        ),
    )
}

sealed interface SignInResult {
    data class Success(val session: StaffSession.SignedIn) : SignInResult
    data class Failure(val error: SignInError) : SignInResult
}
