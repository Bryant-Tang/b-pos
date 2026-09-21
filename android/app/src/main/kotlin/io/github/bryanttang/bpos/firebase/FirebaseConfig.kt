package io.github.bryanttang.bpos.firebase

/**
 * 這台平板要連到哪一個 Firebase 專案。
 *
 * **這三個值是真實專案設定，依 CLAUDE.md 第一節一律不進版控。** 它們由 CI 從
 * GitHub Secrets 讀出來、以 Gradle 參數帶進建置，再由 `build.gradle.kts` 寫成
 * BuildConfig 欄位。沒帶的時候（本機建置、PR 的 CI 建置）三個都是空字串，
 * App 仍然編得出來也裝得起來，只是開起來會顯示「這台平板還沒設定」。
 *
 * 為什麼不用 `google-services.json`：那支檔案要搭 google-services plugin，而它含
 * 完整的專案設定，放進這個公開 repo 等於把設定公開。改成三個值從 Secrets 進來，
 * 值不會出現在任何一個檔案裡。
 *
 * 順帶一提，這裡的 apiKey 不是密碼——Android 的 API 金鑰本來就會跟著 APK 出去，
 * 擋存取的是 Firestore Rules 與 App Check，不是這把金鑰。不進版控的理由是
 * 「這個 repo 是公開的，而它是真實專案的識別資訊」，不是「它能解鎖什麼」。
 */
data class FirebaseConfig(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
)

/**
 * 把建置帶進來的三個字串變成設定；**任何一個是空的就回 null**。
 *
 * 缺一個就整組不成立，不做部分初始化：只有 projectId 沒有 apiKey 的話，
 * Firestore 連得上但登入永遠失敗，店員看到的是「帳號密碼不對」——
 * 那是最難查的那種錯。寧可一開始就講「這台平板還沒設定」。
 */
fun firebaseConfigOf(projectId: String, applicationId: String, apiKey: String): FirebaseConfig? {
    val trimmed = listOf(projectId, applicationId, apiKey).map { it.trim() }
    if (trimmed.any { it.isEmpty() }) return null
    return FirebaseConfig(
        projectId = trimmed[0],
        applicationId = trimmed[1],
        apiKey = trimmed[2],
    )
}
