# Reuse assessment (2026-10-01)

Historical research record. Current implementation and model evidence: [DocQuadNet-256](LEARNED-DETECTOR-RC8.md); current release: [RC10](ENHANCEMENT-RC10.md).
Original SDK code uses Apache-2.0. No scanner implementation source has been copied. OpenCV supplies the image-processing algorithms rather than recreating native vision primitives. AndroidX/CameraX supply capture/lifecycle/UI support. Android PdfDocument provides offline image PDF export.

| Candidate | Source and actual license | Dependencies / suitability | Decision |
| --- | --- | --- | --- |
| OSS Document Scanner (ossappscollective, formerly Akylas) | Public main source; LICENSE is MIT, copyright 2022 Martin Guillon | NativeScript/Svelte application. package.json includes optional app purchase, HTTP and Sentry libraries. Native processor Android build includes OpenCV/native components and iText Android kernel/layout/bouncy-castle adapter; submodules zxing-cpp and app-tools. Extraction would require independent licenses/notice review for each native/third-party component; app MIT alone is not sufficient. Modern native build explicitly uses flexible-page-size flag; supported ABIs include 32/64-bit. Native binary size and measured quality not established in this workspace. | Do not import whole application/runtime or native processor into initial SDK. Rich processing is a future candidate only after isolated dependency/licensing review. |
| CleanSCAN | Public master source; Apache-2.0 root LICENSE | App build compile/target 29; scanlibrary module. Actual app dependency manifest includes Play services Vision 20.1.2, Firebase core and ML Vision, Google services plugin. Older lifecycle/storage stack and bundled vision processing need substantial extraction and native/license upgrades. No current quality/native-size evidence established here. | Reject app as dependency due to mandatory unwanted components. No extracted source copied. |
| OpenCV Android 4.12.0 | Apache-2.0 OpenCV >=4.5; official Maven org.opencv:opencv | Contours, perspective transforms and color tools; packaged native .so files; no Play services or model downloads. Standalone core API adapter keeps types private. Binary alignment/size measured on actual AAR/APK separately. | Adopt pinned Maven AAR; preserve upstream notices and dependency graph. Classical pipeline, conservative manual fallback, measured synthetic baseline. |
| ML Kit document-scanner sample | Public Apache sample code | Example delegates scanner engine/UI to Play services. Sample license grants no scanner engine, model or UI implementation. | No engine/code reuse. |

Inspected primary sources:

- https://github.com/ossappscollective/OSS-DocumentScanner/blob/main/LICENSE
- https://github.com/ossappscollective/OSS-DocumentScanner/blob/main/package.json
- https://github.com/ossappscollective/OSS-DocumentScanner/blob/main/plugin-nativeprocessor/platforms/android/include.gradle
- https://github.com/ossappscollective/OSS-DocumentScanner/blob/main/.gitmodules
- https://github.com/clean-apps/CleanSCAN/blob/master/LICENSE
- https://github.com/clean-apps/CleanSCAN/blob/master/app/build.gradle
- https://opencv.org/license/
- https://developer.android.com/media/camera/camerax/transform-output
- https://developer.android.com/guide/practices/page-sizes

No quality equivalence to Google is claimed. Choosing a small native adapter over application extraction avoids accounts/library/history/OCR integration while keeping first-launch offline operation. Model runtime, weights and datasets would require separate reviews if ML is introduced later.


The final OpenCV decision excludes the official native AAR after x86 build information revealed IPP. The scanner adopts Apache-licensed Java wrappers and independently builds the required open-source native modules. See NATIVE-BUILD.md for immutable provenance and disabled optional components.
