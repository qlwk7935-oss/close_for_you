plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// D: 합성 팝업 재현용 더미 앱. 본 앱(:app)과 서로 의존하지 않는 별도 APK다.
android {
    namespace = "kr.closeforyou.popuptest"
    compileSdk = 35
    defaultConfig {
        applicationId = "kr.closeforyou.popuptest"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-test"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// 시험 전용: release 빌드를 만들지 않는다.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
}
