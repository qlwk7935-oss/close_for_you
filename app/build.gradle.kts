import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Debug artifact identity includes the actual working-tree bytes (including untracked A code).
// No Git command, path, local setting or user data is embedded. Configuration cache is not enabled.
val issue29SourceHash = MessageDigest.getInstance("SHA-256").let { digest ->
    val sources = fileTree("src/main") { include("**/*.kt", "**/*.xml") }.files +
        fileTree("src/debug") { include("**/*.kt", "**/*.xml") }.files +
        listOf(file("build.gradle.kts"), rootProject.file("build.gradle.kts"), rootProject.file("settings.gradle.kts"),
            rootProject.file("gradle/libs.versions.toml"), rootProject.file("gradle/wrapper/gradle-wrapper.properties"))
    sources.filter { it.isFile }.sortedBy { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }.forEach {
        digest.update(it.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte()); digest.update(it.readBytes()); digest.update(0.toByte())
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

android {
    namespace = "kr.closeforyou"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.closeforyou"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "ISSUE29_SOURCE_SHA256", "\"$issue29SourceHash\"")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets.getByName("debug").java.srcDir("../synthetic-test-support/src/main/java")

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.8")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // D(F12): 사용자 선택 기억 저장소. 기기 내부 DB만 사용하며 네트워크 권한은 계속 제거된 상태다.
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// The external synthetic host must be a standalone APK, never an app dependency.
// Building instrumentation fixtures also builds the separately installable debug host.
tasks.matching { it.name == "assembleDebugAndroidTest" }.configureEach {
    dependsOn(":capture-test-host:assembleDebug", ":capture-test-host:assembleDebugAndroidTest")
}
