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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("androidTest").java.srcDir("src/testShared/java")
        getByName("test").java.srcDir("src/testShared/java")
    }
}

// Never produce a release host or include it in the production app.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

dependencies {
    // Own runtime classpath: instrumentation's target-app dependency deduplication cannot remove it.
    implementation(kotlin("stdlib"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
