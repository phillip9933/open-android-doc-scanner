plugins { id("com.android.library"); kotlin("android") }
android { namespace="dev.offlinescan.export"; compileSdk=36; ndkVersion="28.2.13676358"
    defaultConfig { minSdk=26; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
}
dependencies { api(project(":scanner-core")); androidTestImplementation("androidx.test.ext:junit:1.2.1"); androidTestImplementation("androidx.test:runner:1.6.2"); androidTestImplementation(project(":scanner-processing-opencv")) }


