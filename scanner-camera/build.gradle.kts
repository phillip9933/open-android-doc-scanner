plugins { id("com.android.library"); kotlin("android") }
android { namespace="dev.offlinescan.camera"; compileSdk=36; ndkVersion="28.2.13676358"
    defaultConfig { minSdk=26; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
}
dependencies { api(project(":scanner-core")); implementation(project(":scanner-processing-opencv"))
    api(libs.camera.view); implementation(libs.camera.camera2); implementation(libs.camera.lifecycle)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7"); testImplementation("junit:junit:4.13.2") }


