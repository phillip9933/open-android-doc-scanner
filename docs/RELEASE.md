# Releases

Current prerelease: **0.8.0-beta.1**. Stable remains **0.7.0**. See [beta notes](RELEASE-0.8.0.md). The SDK is distributed as a Maven ZIP with checksums and license/provenance material; do not attach the developer sample APK as a public product.

## Build and package

```sh
./gradlew :scanner-core:test :scanner-camera:testDebugUnitTest :scanner-ocr-tesseract:testDebugUnitTest :scanner-sample:assembleDebug lint
./gradlew publishAllPublicationsToLocalReleaseRepository
python3 tools/package_maven_release.py
python3 tools/verify_ocr_consumer.py
```

The local-only publication target is `release/maven`. The archive contains six coordinates under `dev.offlinescan`, version `0.8.0-beta.1`, including optional `scanner-ocr-tesseract`. Native source and notice requirements remain in [NATIVE-BUILD.md](NATIVE-BUILD.md).

```kotlin
maven { url = uri("/absolute/path/to/extracted-sdk/maven") }
// Add to host dependencies; update every scanner module together and recompile.
implementation("dev.offlinescan:scanner-ui-compose:0.8.0-beta.1")
// Optional:
implementation("dev.offlinescan:scanner-ocr-tesseract:0.8.0-beta.1")
```

Before publishing, validate the exact versioned archive, independent consumers, checksums and notes. Publish a GitHub prerelease for beta tags and preserve the latest stable release. The current workflow builds/audits; it does not automatically publish packages. Real-world beta feedback is permitted before stable acceptance; unverified gates stay open. See [FIELD-TESTING.md](FIELD-TESTING.md).

## Evidence commands and historical validation

Use connected supported emulators/devices for `connectedDebugAndroidTest` on processing, export and sample. To preserve benchmark JSON before test-app uninstall, install the processing test APK directly, run `adb shell am instrument -w dev.offlinescan.processing.test/androidx.test.runner.AndroidJUnitRunner`, then pull its external-files benchmark/resource JSON. Inspect `getconf PAGE_SIZE` for a16KB emulator; don't infer page size from ABI/name alone.

Run tools/audit_release.py on the actual final sample APK and saved sample dependency graph. Run tools/check_quality.py against a freshly measured benchmark JSON. Full evidence and SHA256 indexes live under evidence/release.

`python tools/package_release.py` is the full sample APK/source packaging path and requires a current passing APK audit; it is separate from the SDK Maven archive. An independent app compiled solely against the local Maven repository; its packaged native/permission checks are in evidence/published-consumer-audit.json. This is generic artifact consumption evidence, not acceptance in OpenCloud or Kura.

Before a stable production release, finish DEVICE-ACCEPTANCE.md, real arm64 camera/16KB tests, TalkBack/large-font UX, realistic held-out quality/auto-capture metrics, and host integration acceptance. Retain exact native-source provenance/notices. Do not describe this candidate as fully accepted or Google-equivalent.


The RC10 GitHub APK is a debug-signed standalone sample rebuilt for publication. OpenCV compiler/diagnostic paths are anonymized, with a fresh native manifest and artifact hashes; document-processing code and model weights are unchanged. Processing, export and UI checks are repeated against this exact public build. It is for testing, not a Play Store release. Historical evidence paths have been redacted for publication; measurements and artifact hashes are retained. `.gitignore` excludes release outputs, caches, machine settings and signing keys.

Public-build validation: `evidence/publication-processing-*.txt` passes 30 processing checks on each emulator (two opt-in diagnostics skipped); export passes 2/2 and sample/UI passes 8/8, including API37.1 at 200% font. `publication-release-audit.json` and `publication-consumer-audit.json` pass against the anonymized native rebuild. The unchanged synthetic detector gates pass. Native build/SDK machine paths are absent from both rebuilt OpenCV binaries.
