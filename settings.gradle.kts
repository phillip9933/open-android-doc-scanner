pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "open-android-doc-scanner"
include(":scanner-core", ":scanner-processing-opencv", ":scanner-camera", ":scanner-ui-compose", ":scanner-export", ":scanner-sample")
