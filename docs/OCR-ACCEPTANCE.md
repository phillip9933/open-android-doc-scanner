# OCR candidate acceptance — 26 October 2026

The optional 0.8.0 implementation is available for review; this is not a stable-release claim. Issue #2 owns physical document/card acceptance and #5 owns promotion.

## Automated evidence

- Core/camera/OCR JVM tests and lint pass. Existing core tests exercise order, edits, original/output ownership, cancellation cleanup and batch limits.
- Four real-engine tests pass on the Pixel 10 Pro (Android 17 / API 37, arm64, 4 KB pages) and API 26 x86_64 emulator: fixed Japanese/English text, leading-zero numeric region/full-image coordinates, untrusted-model rejection/cleanup, and cross-instance concurrency rejection.
- The Pixel fixed bilingual fixture took 423 ms. Sampled process peak PSS was 85,674 KiB; sampling every 50 ms includes the instrumentation harness and model copies, is not an exact allocation maximum, and may miss short peaks. This single small 1600 × 500 fixture is not a full-page or sustained-performance benchmark.
- The sample OCR UI test passed on Pixel and API 35: English/card layout, recognized text, word highlights, 100% inspection, and clearing stale results on language change.
- Independent hosts compile from the archived Maven artifacts with and without OCR. The plain host contains no Tesseract; neither host accidentally bundles language models. See [consumer evidence](../evidence/ocr-consumer-validation.json).
- Snapshot packaging resolves timestamped publication metadata, excludes stale builds, and includes OCR documentation/provenance. Three package regression tests pass, including missing inputs and reproducible archive bytes.
- Native source hashes, ELF alignment/hardening and final APK provenance/license checks pass. Removing the packaged OCR libjpeg notice makes the audit fail as expected.

## Known accuracy finding

The original test generated `会員番号` with Android's default Canvas fallback font at 80 px. API 35 recognized it; the Pixel's Android 17 input was recognized as `会具番号`. English digits were correct in bilingual mode. Swapping language order or Document/Sparse layout did not repair that character; Japanese-only also misread a digit. Do not silently substitute characters or normalize identifiers to numbers.

The regression suite now uses a committed fixed-pixel Noto Sans CJK fixture so native behavior can be compared on identical input across devices. That fixture passes; the platform-font result above remains a known limitation, not a corrected bug. Hosts must allow users to compare and correct names/numbers. Real photographs are required to assess practical impact.

## Remaining physical acceptance

The updated sample is installed on the owner's Pixel. Pending owner observations:

- English and Japanese printed documents, including small/light text, shadow and glare.
- Printed mock cards/front/back with a name and an identifier such as `001234`; compare exact identifiers, not numeric values. Handwriting is outside the promised scope.
- Recognition time and inspection usability on exported images; Original/Photo versus Auto where cleanup removes light print.
- Repeat after a failure or cancellation, verify originals/exports remain available, and record any unacceptable recognition errors.

Do not publish private scan images. Record redacted expected/actual text, settings and approximate timing instead. These checks are still pending and block stable promotion; synthetic results are not real-camera acceptance.

## Repeat automated checks

```sh
python3 tools/prepare_ocr_test_models.py
./gradlew :scanner-core:test :scanner-camera:testDebugUnitTest :scanner-ocr-tesseract:testDebugUnitTest lint
./gradlew :scanner-ocr-tesseract:assembleDebugAndroidTest :scanner-sample:assembleDebugAndroidTest
# On each dedicated test device:
adb -s DEVICE install -r scanner-ocr-tesseract/build/outputs/apk/androidTest/debug/scanner-ocr-tesseract-debug-androidTest.apk
adb -s DEVICE shell am instrument -w dev.offlinescan.ocr.test/androidx.test.runner.AndroidJUnitRunner
./gradlew publishAllPublicationsToLocalReleaseRepository
python3 tools/package_maven_release.py
python3 tools/test_package_maven_release.py
python3 tools/verify_ocr_consumer.py
python3 tools/check_ocr_native.py
python3 tools/audit_release.py --self-test
python3 tools/audit_release.py
```
