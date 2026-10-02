plugins {
    id("com.android.library") version "8.13.2" apply false
    id("com.android.application") version "8.13.2" apply false
    kotlin("android") version "2.2.20" apply false
    kotlin("jvm") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
allprojects { group = "dev.offlinescan"; version = "0.1.0-rc10" }
subprojects {
    if (name != "scanner-sample") {
        val moduleProject = this
        apply(plugin = "maven-publish")
        plugins.withId("com.android.library") {
            extensions.configure<com.android.build.gradle.LibraryExtension> { publishing { singleVariant("release") { withSourcesJar() } } }
        }
        gradle.projectsEvaluated {
            moduleProject.extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("scanner") {
                        from(moduleProject.components[if(moduleProject.name == "scanner-core") "java" else "release"])
                        pom {
                            name.set(moduleProject.name)
                            description.set("Offline Apache-2.0 Android scanner SDK")
                            licenses { license { name.set("Apache License, Version 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") } }
                        }
                    }
                }
                repositories { maven { name="LocalRelease"; url=uri(rootProject.layout.projectDirectory.dir("release/maven")) } }
            }
        }
    }
}


