plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.starkmesh.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.starkmesh.app"
        minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui"); implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3"); implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0"); implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303"); implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.google.crypto.tink:tink-android:1.15.0"); implementation("androidx.datastore:datastore-preferences:1.1.1")
    val cx = "1.4.0"
    implementation("androidx.camera:camera-core:$cx"); implementation("androidx.camera:camera-camera2:$cx"); implementation("androidx.camera:camera-lifecycle:$cx")
}
