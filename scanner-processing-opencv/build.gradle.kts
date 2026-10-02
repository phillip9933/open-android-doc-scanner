plugins { id("com.android.library"); kotlin("android") }
android { namespace="dev.offlinescan.processing"; compileSdk=36; ndkVersion="28.2.13676358"
    defaultConfig { minSdk=26; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"; ndk { abiFilters += listOf("arm64-v8a","x86_64") } }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
}
dependencies { api(project(":scanner-core")); implementation(files("libs/opencv-java-4.12.0.jar")); implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1"); androidTestImplementation("androidx.test:runner:1.6.2") }


