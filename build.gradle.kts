plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    // D(F12): Room 어노테이션 처리. Kotlin 2.1.20과 버전을 맞춘다.
    id("com.google.devtools.ksp") version "2.1.20-1.0.32" apply false
}
