plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android { namespace="dev.offlinescan.sample"; compileSdk=36; ndkVersion="28.2.13676358"
    defaultConfig { applicationId="dev.offlinescan.sample"; minSdk=26; targetSdk=36; versionCode=11; versionName="0.1.0-rc11"; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"; ndk { abiFilters += listOf("arm64-v8a","x86_64") } }
    buildFeatures { compose=true }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
}
dependencies { implementation(project(":scanner-ui-compose")); implementation("androidx.activity:activity-compose:1.10.1") }
dependencies {
    androidTestImplementation(project(":scanner-camera"))
    androidTestImplementation(project(":scanner-processing-opencv"))
    androidTestImplementation("androidx.camera:camera-view:1.4.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
tasks.register("writeDependencyInventory") {
    doLast {
        val runtime=configurations.getByName("debugRuntimeClasspath")
        val output=rootProject.file("evidence/runtime-artifacts.tsv")
        output.parentFile.mkdirs()
        val artifacts=runtime.incoming.artifactView { componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier } }.artifacts.artifacts
        output.writeText(artifacts.sortedBy { it.id.componentIdentifier.toString() }.joinToString("\n") {
            val id=it.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
            "${id.group}\t${id.module}\t${id.version}\t${it.file.name}"
        })
    }
}



