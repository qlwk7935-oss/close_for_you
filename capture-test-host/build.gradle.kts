plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.closeforyou.capturetest"
    compileSdk = 35
    defaultConfig {
        applicationId = "kr.closeforyou.capturetest"
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

// Never produce a release host or include it in the production app.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

dependencies {
    // Own runtime classpath: instrumentation's target-app dependency deduplication cannot remove it.
    implementation(kotlin("stdlib"))
}
