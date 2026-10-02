# Standard detection improvements for RC3

2026-10-01. Standard detection now recovers substantially more faint, shaded, blurred and angled synthetic documents. No ML weights were adopted. RC2 APK/source artifacts remain immutable. The detector was frozen before reviewing held-out challenge results; no changes followed that review.

## Change

Strong Canny edges retain the first pass. A weaker edge pass and last-resort Otsu segmentation recover boundaries that the previous fixed thresholds missed. Threshold-only closing connects thin ink cuts. Contour and convex-hull approximations propose corners at several tolerances; hulls never become inferred bounding boxes. Four straight sides must remain supported by the original contour and inside/outside grayscale contrast. Fitting those side lines and intersecting them refines the corners. Clipped boundaries, low solidity and degenerate geometry are rejected. The candidate area floor falls from 6% to 1.5% for receipts, with independent side-support requirements. Confidence incorporates observed edge evidence and is capped at **0.94**, below the previous 0.98 cap.

The upright normalized corner contract, EXIF handling, scene signature, brightness/sharpness measurements, original pixels and render presets are unchanged. Contour areas are cached to reduce JNI calls during ranking. All additional native temporaries retain deterministic cleanup.

## Method and measured quality

The unchanged original benchmark has 24 fixtures: 10 documents and two negatives per split. A separate original challenge cohort adds 20 fixtures: six documents and four negatives per split, covering faint light backgrounds, broad shadows, narrow receipts, clutter, strong perspective, soft edges, circles, crosshatching, clipped pages and triangles. Its asset manifest/truth were generated before detector edits. Only tuning results informed changes; held-out results were withheld until the detector was frozen. Both cohorts are synthetic, not evidence of real camera acceptance.

Before/after quality comes from actual `OpenCvProcessor.detectBitmap` calls. IoU clips convex polygons and includes misses as zero. Corner error averages corresponding normalized corner distances on detected matches. Final geometry results are identical on the API 35 emulator and API 37 emulator configured for 16 KB pages.

| Held-out quality | Before | RC3 |
|---|---:|---:|
| New challenge documents detected | 1/6 | **5/6** |
| New challenge false positives | 0/4 | **0/4** |
| New challenge mean polygon IoU, including misses | 0.16559 | **0.83086** |
| New challenge mean corner error on matches | 0.002295 | 0.000701 |
| Original documents detected | 8/10 | **10/10** |
| Original false positives | 0/2 | **0/2** |
| Original mean polygon IoU, including misses | 0.79887 | **0.99628** |

Challenge tuning improved from 1/6 to 6/6 documents, with 0/4 false positives and IoU 0.99613 after the change. The original held-out recoveries are `paper-light` and `blur`.

Corner-error match sets differ when recall improves. For the original eight documents detected in both versions, paired mean corner error rises from **0.000378 to 0.000869**, still below the unchanged 0.005 gate. This revision improves recall and overlap; it does not improve every individual corner measurement. The remaining challenge miss is **held-out-narrow-receipt**. Its failure was retained as evidence rather than used for another tuning pass.

## Latency, validation and limits

Fixture decoding is excluded and OpenCV is explicitly warmed. Cohort timings use one measured call per fixture, so comparisons are descriptive rather than a statistically controlled speed study.

| API 35 held-out cohort | Before mean / p95 | RC3 mean / p95 |
|---|---:|---:|
| New challenge, 10 cases | 6.14 / 19.15 ms | **10.98 / 25.88 ms** |
| Original, 12 cases | 5.05 / 8.78 ms | **10.48 / 18.74 ms** |

The 16 KB emulator's final challenge mean/p95 are 18.62/36.76 ms; its original cohort is 11.45/15.68 ms. Additional passes cost time, especially in non-document clutter. A separate measurement takes 20 calls per tuning scene after five warm-ups:

| Scene | API 35 p50 / p95 | 16 KB emulator p50 / p95 |
|---|---:|---:|
| Faint light page | 8.96 / 18.70 ms | 10.85 / 14.14 ms |
| Clutter with page | 7.08 / 24.69 ms | 11.59 / 20.65 ms |
| Negative crosshatching | 24.46 / 41.70 ms | 23.45 / 30.52 ms |
| Clipped page | 11.30 / 23.81 ms | 15.66 / 21.86 ms |

Managed/native heap values around these calls are endpoint samples, **not peaks or proof of leak freedom**; GC and allocator reuse affect them. The independent bounded-render resource test also passed, but does not measure detection's exact peak allocation.

The lead ran **13 processing instrumentation tests on each emulator**, all passing: original processing invariants, unchanged benchmark, new challenge measurements, tuning quality/live-still parity, repeated latency and resource measurement. The original gates passed on both: recall ≥80%, zero fixture false positives, IoU ≥0.79, matched corner error ≤0.005 and emulator analysis p95 ≤100 ms.

Limits remain: the detector recognizes supported quadrilateral boundaries, not document semantics; screens, frames and other rectangular objects can be selected. Missing/curved/glared edges and some narrow receipts require manual corners. A recovered outline is not capture approval: the held-out faint page has brightness 0.91678 but sharpness 23.75, and the soft-edge page has sharpness 0.266, below the existing focus gate. Automatic capture must retain raw geometry, focus and stability checks. Physical arm64 devices, real held-out footage, sustained thermal performance, temporal outline movement and duplicate-capture behavior still need validation; emulator results establish none of those.

## Files and evidence

Changed production implementation: `scanner-processing-opencv/src/main/kotlin/dev/offlinescan/processing/OpenCvProcessor.kt`; module algorithm/limits documentation: `scanner-processing-opencv/README.md`. Added `DetectionChallengeTest.kt` and the separate `src/androidTest/assets/challenge/` manifest/20 PNGs. Existing benchmark fixtures and gates were preserved. This report records the completed detection task; camera/UI changes have separate ownership.

Primary local evidence: [challenge baseline](../evidence/rc3-detection-baseline.json), [API 35 challenge](../evidence/rc3-detection-challenge-results-api35.json), [16 KB challenge](../evidence/rc3-detection-challenge-results-16k.json), [original API 35 benchmark](../evidence/rc3-benchmark-results-api35.json), [original 16 KB benchmark](../evidence/rc3-benchmark-results-16k.json), [API 35 repeated latency](../evidence/rc3-detection-latency-results-api35.json), [16 KB repeated latency](../evidence/rc3-detection-latency-results-16k.json), and [13-test API 35 output](../evidence/rc3-processing-api35.txt). Original-version comparisons use `benchmark-api35-final.json` and `benchmark-16k-final.json` in the same evidence directory.
