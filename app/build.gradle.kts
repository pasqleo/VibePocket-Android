plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}
android {
  namespace = "it.vibepocket"
  compileSdk = 35
  defaultConfig {
    applicationId = "it.vibepocket"
    minSdk = 26
    targetSdk = 35
    versionCode = 2
    versionName = "0.2.0"
    ndk { abiFilters += listOf("arm64-v8a") }
  }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
dependencies {
  implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
