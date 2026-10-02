# Measured quality and release status

Current public prerelease: RC10. See [enhancement and latest checks](ENHANCEMENT-RC10.md) and [learned detection](LEARNED-DETECTOR-RC8.md). Earlier sections below are historical records.

Local 0.1.0-rc1 candidate, 2026-10-01. Independently implementable first-release work is implemented and locally packaged. Required physical-device and host acceptance is unverified. This is not a Google-parity claim or a fully accepted release.

## What actually passed

- Build, Android lint (zero errors), five local Maven publications and independent published-artifact consumer compilation. Final records: evidence/final-build.txt, final-attribution-build.txt and published-consumer-build.txt.
- 5 core JVM and 16 camera JVM tests: valid/invalid geometry, transforms, owned import/replace/reorder/cleanup, automatic capture temporal gates, preview mapping and capture file ownership. Recorded XML reports are copied into evidence/unit-tests.
- On API35 x86_64 and API37.1 x86_64 with measured PAGE_SIZE=16384: 10 processing instrumentation tests each (EXIF all eight orientations, actual transform/color/tonal fixtures, uniform negatives, limits/cancellation, detector/resource benchmarks), 2 actual image/PdfRenderer export tests each, and 4 full Compose/sample tests each. Final suite totals: 21 JVM tests plus 16 Android tests per emulator.
- UI tests use the public ready-made flow, actual synthetic imported images, crop/appearance, two-page reorder, image PDF dimensions/order, typed cancel/error, and CameraX synthetic-camera manual shutter. Dark theme at 200% font was visually reviewed; scrollable controls were exercised. This does not establish TalkBack acceptance.
- Both emulators ran with Wi-Fi/data disabled and Google Play services disabled before the tested launch. These images still have Play services installed: a physically absent Play-services device remains an acceptance check.
- Final source-built native runtime is core/imgproc/java only, no IPP/optional downloaded engine. Both ABIs passed module/JNI/dynamic-dependency/ELF 16KB checks. Final APK checks include manifest, forbidden dependency scan, uncompressed native ZIP 16KB offsets, native provenance and embedded notice preservation. See release-audit.json and native-build-manifest.json; arm64 binaries were audited but not executed on physical hardware.

## Synthetic detector and resource baseline

24 generated, Apache-licensed examples, 12 tuning and 12 held-out. Each split has ten positives and two negatives; partial-boundary images are negative by explicit fixture annotation because no complete quadrilateral is visible. Fixtures cover receipts/cards, colored forms/marks/faint text, photographs, light/dark backgrounds, perspective/rotation, shadow, blur and negatives. The held-out fixtures were generated before implementation and were not used to tune a later detector iteration.

| Runtime | Held-out detections | False positives | Mean IoU including misses | Mean normalized corner error on matches | All-case warm analysis p95 | 5.76MP render range |
| --- | --- | --- | --- | --- | --- | --- |
| api35 | 8/10 | 0/2 | 0.798867 | 0.000378 | 9.75 ms | 206.6–549.2 ms |
| 16k | 8/10 | 0/2 | 0.798867 | 0.000378 | 15.06 ms | 264.6–658.3 ms |

Both final runs pass tools/check_quality.py gates established after the first baseline: held-out detection >=80%, zero false positives, IoU >=0.79, matched normalized corner error <=0.005, warm emulator analysis p95 <=100ms. Held-out light paper on light background and blur fail detection; manual crop remains available. Two negative examples cannot establish a reliable real-world false-positive rate. Matched-only corner error excludes missed detections; IoU includes them as zero.

Detection timing is a single warmed pass, excludes fixture decoding, and is emulator-specific. Detection heap values are endpoints, not peaks. Resource tests sample managed/native heap every 2ms around a single 3200x1800 render for each preset; sampled maxima are about 112.2 MiB native and 17.9 MiB managed. They are approximate, not RSS/PSS or a worst-case 50-page session bound; short allocations may be missed. No physical latency claim is made.

Auto-capture unit sequences check stability/exposure/sharpness, no overlaps, timeout/reset and duplicate lockout/page replacement. Real duplicate/missed capture rates are not measured; the 30-trial physical recipe is in DEVICE-ACCEPTANCE.md. Content fidelity tests use real transformed pixel landmarks, conservative preset/color checks and all EXIF orientations. They do not quantify clipping of real faint text, handwriting or glare.

## Corrections and evidence limits

The stock OpenCV native AAR baseline exposed IPP in x86 build information and was rejected. Stock benchmark/audit records are retained as history; final evidence is explicitly named *-final.json and final build/audit files. The source build verifies the archive and extracted files, disables optional engines and forbids dependency downloads. Full copyright-bearing OpenCV source notices and NDK notices are preserved, as are CameraX's declared libyuv BSD terms. CameraX does not expose the precise vendored libyuv revision; this is an attribution provenance limitation, not an invented version.

API37's original UI harness failed at InputManager reflection before interaction. Test-only AndroidX Test/Espresso pins were updated to the official fix (Espresso3.7/runner1.7/junit1.3). A subsequent synthetic-camera mismatch was removed by configuring rear virtualscene and front emulated cameras; final ui-16k-final-camera-configured.txt passes all4 tests. API35 final-attribution UI suite also passes all4. Earlier failures are retained and not counted as passes.

Final sample lint reports only the intentional API36 target versus API37 tooling and newer Activity/Compose versions. All-module lint also records newer dependency suggestions. The sample explicitly excludes every storage domain from cloud backup and device transfer, in addition to allowBackup=false/fullBackupContent=false; hosts must choose their own backup policy. No lint errors were suppressed to report success. Final packaged UI evidence is ui-api35-delivery.txt and ui-16k-delivery.txt (all4 pass each).

## Limits and exact remaining acceptance

- Min API26, compile/target36, arm64-v8a/x86_64 only; no 32-bit support. Execution currently verified only on x86_64 emulators. No front-camera mirroring promise.
- Import bounds encoded bytes/pixels. Decode is capped at 4096 source edge / 6MP, render at 8MP and configured dimension; returned warnings/dimensions expose downsampling. PDF aggregate raster budget is16MP, edge <=2048, image-based PDF only; PDF points currently follow rendered pixels. No OCR/searchable PDF, dewarping, glare stacking, generative cleanup or cloud.
- One launch creates one session, no history. Immutable private original copies and parameter edits are preserved until session close; successful exports belong to the host. Interrupted sessions discard, with no recovery journal. Host stale-directory cleanup/process-death behavior requires acceptance.
- Follow DEVICE-ACCEPTANCE.md on real API26 and modern arm64 devices (including16KB): camera orientation/preview alignment, focus/torch/thermal/lifecycle, content fidelity, duplicate/missed shutter metrics, resource limits, independent viewers, denied/revoked permissions, no Play services installed, light/dark/TalkBack/switch/max-font, interruption and storage faults.
- Build and exercise actual OpenCloud/Kura routes using INTEGRATION.md. Their repository APIs, account/navigation/storage/backup behavior and version compatibility remain unverified. No host repository was modified.

Usage and unavailable task-level billing are recorded in USAGE.md. No public push/publication, production account, paid service or private document was used.

## UI testing release — 0.1.0-rc2 (2026-10-01)

The sample opens directly into automatic scanning. Captures and imports append pages and keep the camera open; the page strip opens review. Review offers enhancement, filters, crop/rotate, retake, delete, page order and add-page actions, followed by a separate local export screen. Precision crop and appearance controls are in secondary panels. Original icons and blue sample colors follow the supplied screen structure without redistributing the reference screenshots.

Automatic capture can be paused without rebinding the camera. Its successful-scene lock survives review and failed retakes; a capture locks the scene observed at shutter time rather than a subsequent frame. The host's autoCapture=false setting remains authoritative.

RC2 validation: 27 core/camera JVM tests; four end-to-end UI tests each on API35 and API37.1 with 16KB pages, including 200% text, import/edit/reorder/PDF, two consecutive camera captures, discard confirmation and typed cancellation/failure. See evidence/rc2-reference-ui-api35.txt and evidence/rc2-reference-ui-16k.txt. The processing/native implementation is unchanged from the measured RC1 baseline. Real-camera automatic capture quality and TalkBack still require device acceptance.

The UI-only standalone APK is produced before beginning learned detection. Next: evaluate a small DocAligner model, verify code and weights licenses, compare suitable offline Android runtimes and identical-footage performance, then expose Standard/AI in settings with shared corners and capture/crop/page flow. Standard remains default until comparisons support a change. Simultaneous multi-document detection remains a separate future backlog item.

## Detection and save release — 0.1.0-rc3 (2026-10-01)

Standard detection now uses complementary strong/weak edge and threshold proposals, bounded adaptive polygon approximation, convex-hull proposals, fitted observed sides and four-side contrast/support checks. This is classical detection, not a learned model. Separate challenge tuning/held-out assets were generated before implementation; existing benchmark assets/gates were unchanged. After freezing the detector, challenge held-out detection improved from 1/6 to 5/6 with 0/4 false positives; original held-out detection improved from 8/10 to 10/10 with 0/2 false positives. See DETECTION-IMPROVEMENTS-RC3.md for IoU, matched-set precision tradeoff, latency and the remaining narrow-receipt miss. Synthetic results are not real-camera acceptance.

Camera outlines smooth small jitter for display and immediately reset when the document is lost or moves substantially. Raw detections still drive the shutter, scene signature and duplicate lock. Mean brightness ceiling increased from .90 to .98 based on the bright tuning page; confidence, sharpness and area requirements remain. Thirty-five camera plus five core JVM tests pass.

Save now follows the supplied layout: close/title/Save bar, centered preview/page count, outlined editable filename and clear control, PDF/JPEG pills, outlined local location. Filename stems are validated before any output directory is created; they control real PDF/JPG filenames, with numbered suffixes for multiple JPEG pages. Format changes clear input focus. PNG is not a UI export choice; the processing/export library retains PNG for internal lossless PDF rendering and existing SDK compatibility. Location/account/upload belong to the host when integrated; the standalone does not invent a cloud account.

Thirteen processing tests, two export tests and four full UI flow tests passed on each API35 and API37.1/16KB emulator. The final save layout also passed targeted normal/200% font flow checks after its final layout changes. Lint, both original benchmark quality gates, local SDK publication/independent consumer, and final native/permission/license audit pass. No physical arm64, real footage or thermal acceptance is implied.

DocAligner code licensing and exact official LC100 checkpoint/interface were evaluated after RC2 delivery. Weight redistribution/commercial-use license scope and dataset provenance remain unresolved; no pretrained weights are bundled and Android inference has not been claimed. See AI-DETECTION-EVALUATION.md and AI-DETECTION-GATES.md. Simultaneous multi-document capture remains deferred separately.

## Fullscreen, page actions and shadow detection - 0.1.0-rc4 (2026-10-01)

The camera now fills behind the status bar with centered-fill viewport mapping, padded lower controls, a single outlined shutter and an active-only rotating scan indicator. Review/save support swiping and arrows. Save uses original PDF/JPG file icons and separate adjacent Back/Close controls. Retake and delete ask before discarding a selected page; confirmed retakes preserve replacement order. Close asks before discarding the whole unsaved session. See UI-CAPTURE-RC4.md for the interaction and stability rules.

A bounded illumination-normalized proposal fallback runs only after the previous Standard detector finds no accepted candidate. Candidates still require measured sides and raw-image boundary support; normalization does not change originals or grant inflated confidence. New synthetic temporal shadow/motion cohorts were frozen before changing the detector. Held-out correct detection improves from 27/32 to 29/32, with zero false positives in 24 negative frames; found-to-missing transitions fall from three to two. Matched-set corner jitter is approximately unchanged and latency increases. The original and RC3 held-out geometry results/gates remain unchanged. See DETECTION-IMPROVEMENTS-RC4.md for exact measurements, remaining misses, memory and focus limitations.

Five core and 52 camera unit tests pass. Fifteen full processing tests plus a separately executed fallback cancellation test pass on each emulator (16 distinct processing tests each). Four complete UI tests pass on each device at normal/200% text, including confirmed page actions and exported replacement order. The final save-header/icon polish additionally passes the complete import/edit/reorder/retake/delete/browse/export test at both text sizes. Lint, local SDK publication, independent consumer build, release audit and original synthetic quality gates pass. No learned weights are included; the prior AI license gate is unchanged. Physical handheld shadow/focus/capture testing, arm64 performance, sustained thermal behavior and accessibility acceptance remain outstanding.

## Layout and preview continuity - 0.1.0-rc5 (2026-10-01)

Capture reserves its page-strip space before the first page, keeping preview bounds and controls stable. Review thumbnails retain their renderer when selection changes. A session-scoped 16 MiB LRU cache keyed by page/original/edits reuses processed previews across capture, review, save and appearance; edits/removal invalidate obsolete entries and disposal clears the cache. Initial thumbnail loading uses a progress indicator. See UI-CONTINUITY-RC5.md.

Four full Android UI tests pass at normal text size, including exact equal camera bounds before/after the first import and loaded thumbnails remaining present immediately after swipes. The complete import/edit/reorder/retake/delete/browse/PDF workflow also passes at 200% text size. UI/sample lint, local SDK publication, independent consumer build and the final APK native/permission/license audit pass. Detection and the native runtime are unchanged from RC4; these tests establish UI continuity, not physical scanning performance.

## Back, swipe-only navigation and capture-mode menu - 0.1.0-rc6 (2026-10-01)

Review Back now returns to capture without retaking/deleting the page; explicit Retake retains confirmation. Review and save navigation arrows are removed, with swiping and page position retained. The camera guidance pill opens Document, Receipt, Photograph and Card choices. Mode changes preserve earlier pages and apply to subsequent captures/imports. Photograph is manual/full-frame with the Photo preset; document modes use the shared Standard detector and Color document preset, subject to host automatic-capture permission and existing session limits. See NAVIGATION-MODES-RC6.md.

Four end-to-end Android tests pass at normal text size. The complete import/edit/reorder/retake/delete/swipe/save/export flow also passes at 200% text. Checks include non-destructive Back, absent arrow controls, earlier Photo preset preserved after switching mode, Color document preset on the next page, a session starting in Photograph then enabling Document controls, menu suspension of auto scanning, and two consecutive manual captures. UI/sample lint, local SDK publication, final independent consumer build and APK native/permission/license audit pass. Standard detection and native binaries are unchanged; physical scanning acceptance remains separate.

## Positioning labels, best-effort focus and page peeks - 0.1.0-rc7 (2026-10-01)

Capture uses the four exact Position a Document/Photo/Receipt/Card labels. Focus cancellation and unsupported metering no longer use the fatal capture-error path. Review/save show actual neighboring-page slivers in available swipe directions. See UI-FOCUS-PEEKS-RC7.md.

52 camera unit tests, four complete normal-text Android UI tests and the full import/edit/save/export flow at 200% text pass. Checks include rapid preview taps followed by capture, all four labels, directional peeks and absent arrows. Screenshots were inspected. Lint, local publication, independent consumer build and exact APK native/permission/license audit pass. Physical handset acceptance remains pending; detector/native code is unchanged.

## Document quality and experimental offline AI - 0.1.0-rc8 (2026-10-02)

Both detectors now measure untouched document-interior sharpness/brightness, excluding the table and outer paper border. Automatic capture waits briefly for document focus, rechecks fresh corners, compares two encoded original JPEGs, and commits the sharper acceptable original without fusion or recompression. A blurred capture retries without adding a page; manual capture stays available. Text that disappears through blur cannot later qualify as blank paper in the same observed scene. Lifecycle interruption removes staging files and releases the busy state. See QUALITY-METRICS-RC8.md for metric limitations and exact thresholds.

Settings offers Standard (default) and experimental AI. DocQuadNet-256 uses an unmodified, checksum-pinned ORT export with the upstream Apache-2.0 inference grant and full notices. ONNX Runtime 1.24.1 runs CPU inference offline from first launch. Both modes share Detection/Quad, outline tracking, raw stability/capture, crop and page workflow. Mode switches preserve pages and invalidate the previous camera generation. DocAligner weights remain unresolved; no DocAligner checkpoint ships. See LEARNED-DETECTOR-RC8.md for weight-license scope interpretation and training-data provenance caveats.

Quality results are mixed. On the frozen sparse synthetic inputs, AI detects fewer pages than Standard. On five original printed-document diagnostics, AI detects all five while Standard detects four; AI finds the torn-corner example. On three adjacent, author-licensed SmartDoc camera frames, Standard misses all three and AI finds all three. The one locally annotated frame has AI polygon IoU 0.98315; two blurry frames correctly fail shared capture-quality requirements. SmartDoc may overlap training and these are adjacent frames, so this is diagnostic evidence, not independent real-camera acceptance. A paced two-minute/1,200-call API35 CPU run reports zero errors/output changes; warm p95 is 23.54ms and allocator endpoints are approximately stable. This does not establish physical-phone thermals, battery, peak memory, or broad detection improvement.

Five core and 61 camera JVM tests pass. All 25 processing tests pass on API35 and verified 16KB API37.1 x86_64 emulators, including shared quality, learned loading/cancellation/EXIF, Standard geometry and resource regressions. Both original synthetic quality gates pass. Three real CameraX automatic-capture tests pass (original selection, rejection cleanup/manual recovery, lifecycle recovery), with controlled corners/signatures and actual pixel quality. Four existing UI flows pass at normal/200% text on API35; all eight sample tests pass at 200% on the 16KB emulator. The new actual AI settings/live-camera/page-preservation/JPEG flow passes on both. Physical arm64 handheld lighting, shadows, receipts, motion, sustained thermal behavior and host acceptance remain pending; Standard is not promoted to AI by this candidate.

## AI default following handset feedback - 0.1.0-rc9 (2026-10-02)

User physical-phone feedback reports substantially better detection in AI mode than Standard. The standalone app and ready-made flow now open with AI selected. Standard stays available in settings. Existing custom hosts that explicitly construct ScanConfig retain their selection; processing-only OpenCvProcessor remains classical detection. Model bytes, decoding, quality requirements and capture behavior are unchanged. This is a user-requested default change, not a newly trained model or a broad accuracy claim. The live-camera test now checks fresh standalone startup in AI, AI/Standard switching, page preservation and JPEG export.

Refinement should start with original uncropped phone images representing actual AI failures. Separate document/capture groups must be reserved for evaluation before tuning or training. The upstream training pipeline supports labeled own images, but distributed ORT inference weights are not a ready-to-resume training checkpoint. Fine-tuning feasibility and any trainable checkpoint's applicable license must be verified before promising a trained replacement. No personal images or new training were used for RC9.

## RC10 enhancement and gesture update

See [RC10 evidence](ENHANCEMENT-RC10.md) for stronger Auto paper cleanup, per-page filter recommendation and zoom, final emulator test counts and unchanged physical-acceptance limits. Historical measurements above remain their original releases.
