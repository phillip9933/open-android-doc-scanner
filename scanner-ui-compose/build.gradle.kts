plugins { id("com.android.library"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android { namespace="dev.offlinescan.ui"; compileSdk=36; ndkVersion="28.2.13676358"
    defaultConfig { minSdk=26 }
    buildFeatures { compose=true }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
}
dependencies { api(project(":scanner-core")); implementation(project(":scanner-processing-opencv")); implementation(project(":scanner-camera")); implementation(project(":scanner-export"))
    api(platform("androidx.compose:compose-bom:2025.04.01")); api("androidx.compose.material3:material3"); implementation("androidx.compose.ui:ui"); implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1"); implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7"); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1") }
dependencies { implementation("androidx.exifinterface:exifinterface:1.4.1") }


