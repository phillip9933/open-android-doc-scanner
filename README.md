# Open Android Doc Scanner

Independent Apache-2.0 Android document and printed-photo scanner. Kotlin, CameraX, OpenCV and Compose. Core processing works offline without Google Play services, cloud, API keys or model downloads. No network permission or analytics.

**Status: public SDK prerelease (0.1.0-rc11; latest testing APK is 0.1.0-rc10); physical-camera acceptance is pending.** See [quality evidence](docs/QUALITY-REPORT.md) and [backlog](docs/BACKLOG.md). Synthetic results are not parity claims or a substitute for real-camera evaluation.

[Download the latest testing APK (0.1.0-rc10)](https://github.com/phillip9933/open-android-doc-scanner/releases/tag/v0.1.0-rc10) · [Documentation](docs/INDEX.md) · [CI](https://github.com/phillip9933/open-android-doc-scanner/actions)

## Build

Requirements: JDK 17 or 21, Android SDK platform 36, AGP-selected build tools, NDK 28.2.13676358, public Google/Maven Central dependencies. Create an untracked `local.properties` with `sdk.dir=...` or set `ANDROID_HOME`. Run:

```sh
./gradlew :scanner-core:test :scanner-camera:testDebugUnitTest :scanner-sample:assembleDebug lint
./gradlew :scanner-processing-opencv:connectedDebugAndroidTest :scanner-export:connectedDebugAndroidTest
```

The second command requires a connected supported emulator/device. Windows uses `gradlew.bat`. Min Android 26; native arm64-v8a and x86_64. No 32-bit support. The sample has no historical library: each launch is one session. Host owns naming, persistent storage, sharing and cleanup after completion.

## Ready-made flow

```kotlin
MaterialTheme {
    ScannerFlow(
        config = ScanConfig(mode = ScanMode.DOCUMENT, detectionMode = DetectionMode.AI),
        outputDirectory = File(context.filesDir, "scanner-output"),
        onResult = { result ->
            when (result) {
                is ScanResult.Completed -> consume(result.output)
                ScanResult.Cancelled -> closeScanner()
                is ScanResult.Failed -> showFailure(result.error)
            }
        }
    )
}
```

Consume the callback by removing the composable. Outputs stay available after session cleanup. Do not put outputs under the session/cache originals directory. Convert private files to your own FileProvider URIs for sharing; the SDK adds no storage provider or host navigation. Pass app's light/dark MaterialTheme. UI strings can be overridden/localized by hosts.

## Processing-only use

`scanner-core` exposes no Android or OpenCV types. Add `scanner-processing-opencv` for the implementation, `scanner-export` for PDF. Call processing/export on a worker thread, with cooperative cancellation. Preserve cancellation exceptions.

```kotlin
val processor: ImageProcessor = OpenCvProcessor()
ScanSession(config, privateCacheRoot, processor).use { session ->
    val page = session.`import`(hostSourceFile) // independent owned copy
    val detection = processor.detect(page.original, config, token)
    detection.corners?.let { session.update(page.id, page.edits.copy(corners = it)) }
    // Let users confirm/correct the suggested crop before saving.
    val output = AndroidExporter(processor).export(
        session.pages, hostOutputDirectory, ExportFormat.PDF, config, token,
        Progress { completed, total -> reportProgress(completed, total) }
    )
    retain(output) // host owns these committed output files
}
```

Config bounds quality/dimensions/pages/input pixels. Renders may be smaller due to decode/pixel limits; use returned actual dimensions. PDF is image-based with bounded aggregate raster budget. Photograph mode preserves color/detail by default. Document Auto can select colour, grayscale or black-and-white; users can override it with a manual filter. Card front/back mode limits the session to two pages and supplies images only.

Read [integration examples](docs/INTEGRATION.md), [architecture and ownership](docs/ARCHITECTURE.md), [reuse assessment](docs/REUSE-DECISION.md), [third-party notices](THIRD-PARTY-NOTICES.md).


Latest public SDK prerelease: 0.1.0-rc11, which adds the optional `ScannerFlow.saveDestination` host UI slot. The latest testing APK remains 0.1.0-rc10. Automatic capture waits for focus, compares two untouched JPEG originals, and keeps the sharper acceptable document; rejected captures retry without adding a page. Sharpness and brightness come from the document interior. Settings offers AI (default in the sample and ready-made flow, using bundled DocQuadNet-256 CPU inference) and Standard (OpenCV edges/contours). Generated printed-document results are promising, including a torn corner; sparse synthetic scenes are substantially weaker than Standard. See [learned-model evidence and licensing limits](docs/LEARNED-DETECTOR-RC8.md) and [quality checks](docs/QUALITY-METRICS-RC8.md). Physical handheld and sustained arm64 acceptance remain pending.

The fullscreen camera keeps its layout as pages are added. Review and save use swipe navigation, adjacent-page peeks and cached previews. Back preserves pages; retake, delete and close confirm discards. Save supports real output names and PDF/JPEG. Advanced adjustments and detection mode stay in secondary panels. Simultaneous multi-document scanning remains deferred.

RC10 adds per-page Auto enhancement for documents, receipts and cards, stronger bounded paper-lighting cleanup, manual filter overrides, and pinch zoom/pan in review and filter previews. Photograph mode retains Photo. See [enhancement evidence and limits](docs/ENHANCEMENT-RC10.md).
