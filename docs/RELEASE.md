# Releases

Version 0.1.0-rc11 candidate. Original code is Apache-2.0. The first public distribution was prerelease v0.1.0-rc10 at https://github.com/phillip9933/open-android-doc-scanner. RC11 adds a host-owned save destination UI slot to the Compose flow; it is prepared locally for host integration review and has not been published.

## Build and package

Source-built OpenCV JNI for arm64-v8a/x86_64 lives in scanner-processing-opencv/src/main/jniLibs and its pinned Java wrapper JAR in libs. Regeneration is described in NATIVE-BUILD.md. No native binary from the original OpenCV AAR may contribute to final resolution.

```sh
./gradlew :scanner-core:test :scanner-camera:testDebugUnitTest :scanner-sample:assembleDebug lint
./gradlew publishAllPublicationsToLocalReleaseRepository
```

The `LocalRelease` repository is exclusively `release/maven` inside this project. It contains five coordinates under `dev.offlinescan`, version `0.1.0-rc11`, with POM/Gradle metadata, release AARs/core JAR and Android source JARs. For this SDK-only candidate, `python tools/package_maven_release.py` copies that repository into a Maven ZIP with SHA256SUMS, release notes, and all license/notice material. No sample APK is included. Keep THIRD-PARTY-NOTICES.md and third-party native/runtime notices with redistribution; applicable native notice text is also included in SDK assets.

Consume locally:

```kotlin
// Host settings.gradle.kts repositories block:
maven { url = uri("/absolute/path/to/offline-scanner/release/maven") }
// Host dependencies:
implementation("dev.offlinescan:scanner-ui-compose:0.1.0-rc11")
```

Google and Maven Central remain build repositories for public AndroidX/Kotlin dependencies; they are not runtime/cloud services. The scanner adds no INTERNET permission. Hosts should audit their own full graphs/manifests independently.

## Evidence commands

Use connected supported emulators/devices for `connectedDebugAndroidTest` on processing, export and sample. To preserve benchmark JSON before test-app uninstall, install the processing test APK directly, run `adb shell am instrument -w dev.offlinescan.processing.test/androidx.test.runner.AndroidJUnitRunner`, then pull its external-files benchmark/resource JSON. Inspect `getconf PAGE_SIZE` for a16KB emulator; don't infer page size from ABI/name alone.

Run tools/audit_release.py on the actual final sample APK and saved sample dependency graph. Run tools/check_quality.py against a freshly measured benchmark JSON. Full evidence and SHA256 indexes live under evidence/release.

`python tools/package_release.py` is the full sample APK/source packaging path and requires a current passing APK audit; it is not part of the SDK-only RC11 package. An independent app compiled solely against the local Maven repository; its packaged native/permission checks are in evidence/published-consumer-audit.json. This is generic artifact consumption evidence, not acceptance in OpenCloud or Kura.

Before a stable production release, finish DEVICE-ACCEPTANCE.md, real arm64 camera/16KB tests, TalkBack/large-font UX, realistic held-out quality/auto-capture metrics, and host integration acceptance. Retain exact native-source provenance/notices. Do not describe this candidate as fully accepted or Google-equivalent.


The RC10 GitHub APK is a debug-signed standalone sample rebuilt for publication. OpenCV compiler/diagnostic paths are anonymized, with a fresh native manifest and artifact hashes; document-processing code and model weights are unchanged. Processing, export and UI checks are repeated against this exact public build. It is for testing, not a Play Store release. Historical evidence paths have been redacted for publication; measurements and artifact hashes are retained. `.gitignore` excludes release outputs, caches, machine settings and signing keys.

Public-build validation: `evidence/publication-processing-*.txt` passes 30 processing checks on each emulator (two opt-in diagnostics skipped); export passes 2/2 and sample/UI passes 8/8, including API37.1 at 200% font. `publication-release-audit.json` and `publication-consumer-audit.json` pass against the anonymized native rebuild. The unchanged synthetic detector gates pass. Native build/SDK machine paths are absent from both rebuilt OpenCV binaries.
