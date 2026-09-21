package io.github.bryanttang.bpos

import android.app.Application
import io.github.bryanttang.bpos.firebase.FirebaseConfig
import io.github.bryanttang.bpos.firebase.ensureFirebaseApp
import io.github.bryanttang.bpos.firebase.firebaseConfigOf

/**
 * 這台平板的 Firebase 設定，從建置時帶進來的 BuildConfig 欄位讀出來。
 *
 * 沒帶值（本機建置、PR 的 CI 建置）時是 null，見 [firebaseConfigOf]。
 */
fun appFirebaseConfig(): FirebaseConfig? = firebaseConfigOf(
    projectId = BuildConfig.FIREBASE_PROJECT_ID,
    applicationId = BuildConfig.FIREBASE_APP_ID,
    apiKey = BuildConfig.FIREBASE_API_KEY,
)

/**
 * 開機時把 Firebase 初始化起來。
 *
 * 放在 Application 而不是 MainActivity，是因為離線佇列的 WorkManager worker
 * 可能在畫面之前就跑起來（系統重啟 process 後補跑排程），那時候還沒有任何 Activity，
 * 但它一樣要寫 Firestore。
 *
 * 沒有設定值時這裡什麼都不做，也不丟例外：App 要能開起來，才有畫面可以告訴人
 * 「這台平板還沒設定」（見 MainActivity 的 NotConfiguredScreen）。
 */
class BposApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ensureFirebaseApp(this, appFirebaseConfig())
    }
}
