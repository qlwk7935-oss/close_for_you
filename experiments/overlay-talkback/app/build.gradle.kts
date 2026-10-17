plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.team.popup.experiment"
    compileSdk = 36

    defaultConfig {
        // 본 앱과 동시에 설치할 수 있도록 별도 ID를 쓴다.
        applicationId = "com.team.popup.experiment"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-experiment"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
}
