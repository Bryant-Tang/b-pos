import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 把一個 Gradle 參數包成 buildConfigField 要的字串常數字面值。
 *
 * 只收 [A-Za-z0-9:._-]：Firebase 的專案 ID、應用程式 ID 與 API 金鑰都在這個範圍內，
 * 而擋掉引號與反斜線就不可能有值跳脫出字串、變成程式碼。不合格就當成沒帶，
 * 理由同空值——寧可顯示「還沒設定」，也不要編出一個連到半個專案的 App。
 */
fun firebaseField(property: String): String {
    val raw = (findProperty(property) as String?)?.trim().orEmpty()
    val safe = if (raw.matches(Regex("[A-Za-z0-9:._-]*"))) raw else ""
    if (raw != safe) logger.warn("$property 含有非預期的字元，已當成沒有設定")
    return "\"$safe\""
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.bryanttang.bpos"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.bryanttang.bpos"
        // 店裡的平板，不上 Google Play，走 Firebase App Distribution（SPEC 第十節）。
        minSdk = 26
        targetSdk = 35

        // versionCode 用 CI 的 run number，versionName 帶 commit sha，
        // 這樣閃退回報能直接對回程式碼（SPEC 第十二節）。本機建置時是 1 與 dev。
        // AGP 不接受 versionCode 0，所以本機的預設值是 1。
        versionCode = (findProperty("bposVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("bposVersionName") as String?) ?: "dev"

        // 這台 App 要連到哪個 Firebase 專案。三個值都是真實專案設定，依 CLAUDE.md
        // 第一節不進版控：CI 從 GitHub Secrets 讀出來用 -P 帶進來，沒帶就是空字串。
        // 空的時候 App 照樣編得出來、裝得起來，只是開起來顯示「還沒設定」
        // （見 firebase/FirebaseConfig.kt）。這樣 PR 的 CI 不必碰到任何真實值。
        buildConfigField("String", "FIREBASE_PROJECT_ID", firebaseField("bposFirebaseProjectId"))
        buildConfigField("String", "FIREBASE_APP_ID", firebaseField("bposFirebaseAppId"))
        buildConfigField("String", "FIREBASE_API_KEY", firebaseField("bposFirebaseApiKey"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        // AGP 8 起預設關閉。畫面上要顯示 versionName，所以要打開。
        buildConfig = true
    }

    // Robolectric 要讀得到 res/ 與 AndroidManifest.xml 才跑得起來，
    // 沒有這行的話 outbox 的 Room 測試會在載入資源時就死掉。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Kotlin 2.x 的新 DSL，取代 android { kotlinOptions { } }（後者在 AGP 8.7 已標為棄用）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Room 的 schema 匯出到 app/schemas/，並且進版控。
// 這不是產生出來就丟的檔案：之後寫資料庫升級（Migration）時，
// Room 要拿舊版 schema 來驗證升級路徑對不對，沒有它就驗不了。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // 本地資料庫與離線佇列（SPEC 第六節〈離線策略〉）。
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Firestore。這裡只用到 SDK 的型別與 API，建置階段不需要
    // google-services.json（那支檔案是 google-services plugin 才要的，
    // 而且它含真實專案設定，依 CLAUDE.md 不進版控）。
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
}
