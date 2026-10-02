# 0.1.0-rc11 SDK candidate

This SDK-only prerelease candidate adds the optional `saveDestination` Compose slot to `ScannerFlow`. Hosts can render a clickable save-location control and launch their own picker. The slot receives `enabled = false` while scanning or exporting; omitting it preserves the built-in “On this device” display. The callback signature keeps `onResult` last so existing trailing-lambda call sites remain source compatible.

The host continues to own its destination selection, output directory, and any copy or upload after `ScanResult.Completed`. This UI slot does not add a storage picker, permission, SAF behavior, navigation, or network access to the scanner SDK.

RC11 changes only the Compose UI API, sample integration test, package version, and release documentation. The OpenCV native libraries, Java wrapper, model weights, and third-party runtime assets are unchanged from RC10. No sample APK is included in this SDK-only candidate.

Validation completed for this candidate:

- `:scanner-sample:testDebugUnitTest :scanner-sample:assembleDebug :scanner-sample:lintDebug` passed. The sample has no JVM unit tests.
- `:scanner-sample:compileDebugAndroidTestKotlin` passed, including the host destination slot test.
- `publishAllPublicationsToLocalReleaseRepository` passed for all five RC11 Maven coordinates.
- The instrumented UI test was not run because no emulator or device was connected. Physical camera and host application acceptance remain pending.

The installable SDK archive contains the five-module Maven repository, `LICENSE`, `NOTICE`, `THIRD-PARTY-NOTICES.md`, and the complete `third-party/` notice directory. The source release tag and commit will provide GitHub's source archive; this SDK archive does not contain an APK.
