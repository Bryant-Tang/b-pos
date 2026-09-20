plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // AGP 8 起預設關閉。畫面上要顯示 versionName，所以要打開。
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // 印表機連線用：socket 的阻塞式讀寫要丟到 IO dispatcher 上跑。
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
