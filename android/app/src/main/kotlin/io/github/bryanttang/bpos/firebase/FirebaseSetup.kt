package io.github.bryanttang.bpos.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * 把 [FirebaseConfig] 轉成 SDK 要的 options。
 *
 * 只給三個值：專案 ID（Firestore 要）、應用程式 ID 與 API 金鑰（Auth 要）。
 * `google-services.json` 裡還有 storageBucket、gcmSenderId 那些，這個 App 沒用到
 * Storage 也沒用到推播，少給不會怎樣；哪天要用再補，補的時候會是編不過而不是靜默失敗。
 */
internal fun FirebaseConfig.toOptions(): FirebaseOptions = FirebaseOptions.Builder()
    .setProjectId(projectId)
    .setApplicationId(applicationId)
    .setApiKey(apiKey)
    .build()

/**
 * 確保預設的 FirebaseApp 已經初始化，回傳「現在連得上 Firebase 嗎」。
 *
 * 正常的 Android App 靠 google-services plugin 產生的 ContentProvider 自動初始化，
 * 這個專案刻意沒裝那個 plugin（設定值不進版控），所以初始化要自己做，而且要做在
 * **任何人呼叫 `FirebaseAuth.getInstance()` 或 `FirebaseFirestore.getInstance()` 之前**——
 * 那兩支在沒初始化時丟的是 IllegalStateException，畫面上看起來就是「一開就閃退」。
 *
 * 做成冪等（已經有就直接回 true）的理由：Application 開機時會呼叫一次，
 * 而 WorkManager 的 worker 可能在 process 被系統重啟後先跑起來。重複呼叫
 * `FirebaseApp.initializeApp` 會丟 IllegalStateException，那會讓離線佇列送不出去。
 */
fun ensureFirebaseApp(context: Context, config: FirebaseConfig?): Boolean {
    if (config == null) return false
    if (FirebaseApp.getApps(context).isNotEmpty()) return true
    FirebaseApp.initializeApp(context, config.toOptions())
    return true
}
