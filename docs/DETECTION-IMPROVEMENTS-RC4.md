# Shadow and handheld-motion detection for RC4

2026-10-01. Standard detection now loses fewer outlines in a new synthetic shadow/motion cohort. Held-out document frames detected increase from **27/32 to 29/32**, with **0/24 negative-frame false positives**. Improvement is partial: three held-out document frames still fail, and real handheld acceptance remains outstanding. RC3 release artifacts and previous fixture cohorts/gates are unchanged. No ML weights were bundled.

## Bounded change

Existing accepted RC3 candidates return unchanged. Only frames with no accepted candidate reach two new fallback passes: edge detection and segmentation on a grayscale image divided by a coarse smooth illumination field, with amplification bounded in dark regions. This reduces broad shading while preserving potential paper/background transitions. The corrected image is used only to propose boundaries; original pixels, EXIF handling, brightness, sharpness and scene signature are unchanged.

All contour-support, fitted-side, convexity, solidity and clipping requirements remain. Each corrected candidate must also demonstrate a side jump in the untouched image after subtracting a local illumination slope. A broad penumbra or normalization gain alone is insufficient evidence. Confidence uses original edge strength, with a lower **0.86 cap** for corrected candidates. There are no remembered corners, inferred bounding boxes or confidence increases to force capture. Worst-case analysis now has five passes; accepted scenes keep their existing path.

## Independent temporal method

The new cohort contains 112 saved 640x480 frames: eight frames for each of seven scenes in each split. Four document scenes use faint light/shadow boundaries, overlapping shadows, a moving penumbra and a textured light background. Three negative scenes contain shadows alone, texture or circles. Each full scene receives a small translation (up to 2.2/1.8 pixels) and rotation (up to 0.55 degrees), with changing illumination, noise and optical softness. Frame truth is transformed independently and stored in the manifest with file hashes.

The unchanged RC3 processor ran first. Only tuning results informed the single implementation revision; held-out results were withheld until the implementation froze. Original and RC3 cohorts were preserved and evaluated afterward, without retuning. These are raw detector outputs, not camera tracker or capture results.

IoU includes misses as zero. Jitter is the mean per-corner residual between detected motion and independent truth motion on adjacent detected frames. Missing pairs are excluded and their count is reported; recall, outline drops and missing runs prevent omitted frames from masquerading as stability.

| New temporal held-out metric | RC3 baseline | RC4 |
|---|---:|---:|
| Detected and accurately matched document frames (IoU >=0.8) | 27/32 | **29/32** |
| Missed document frames | 5 | **3** |
| False positives on negative frames | 0/24 | **0/24** |
| Mean polygon IoU, including misses | 0.84124 | **0.90354** |
| Found-to-missing transitions | 3 | **2** |
| Longest consecutive missing run | 2 | **1** |
| Mean normalized corner error on matches | 0.0008390 | 0.0008376 |
| Mean truth-compensated corner jitter | 0.0003169 | 0.0003164 |
| Matched adjacent pairs used for jitter | 21 | 23 |

Jitter is approximately unchanged; differing match sets do not establish a precision or smoothing improvement. The benefit is fewer missing outlines. Final geometry/quality metrics are identical on API 35 and the API 37 emulator configured for 16 KB pages. Held-out scene recall is light shadow 6/8, overlapping shadow 7/8, moving penumbra 8/8 and textured light 8/8. Remaining misses are `held-out-light-shadow-00`, `held-out-light-shadow-03` and `held-out-cross-shadow-04`; these were retained as failures rather than used for further tuning.

Tuning document recall improved **22/32 -> 27/32**, IoU **0.68545 -> 0.84125**, found-to-missing transitions **7 -> 5**, and longest missing run **3 -> 1**, with 0/24 false positives both before and after.

## Cost and regression evidence

Timing excludes fixture decoding and follows explicit OpenCV warm-up. Temporal measurements take one call per frame, so these are descriptive emulator sweeps rather than sustained physical-device benchmarks.

| Temporal held-out analysis | Baseline API 35 | RC4 API 35 | RC4 16 KB emulator |
|---|---:|---:|---:|
| Mean latency | 6.24 ms | 9.67 ms | 12.43 ms |
| p95 latency | 10.63 ms | 21.18 ms | 20.69 ms |

The unchanged repeated-tuning latency test takes 20 calls per scene after five warm-ups. RC4 API 35 p95 is 9.78 ms for faint light, 8.34 ms for clutter, **56.85 ms for negative crosshatching** and 25.56 ms for a clipped page. Negative scenes can incur all fallback passes.

Managed/native heap samples are recorded before/after every frame, not during allocations. API 35 held-out median per-call endpoint changes are native **14,400 -> 11,456 bytes** and managed **651,264 -> 659,456 bytes**. They do not demonstrate lower memory, peaks, RSS or leak freedom: GC, allocator reuse and differing surrounding test workloads affect them. Additional float fields are released deterministically; an independent cancellation test exercises interruption after normalization allocation and verifies the caller's signal and borrowed bitmap remain intact.

The measured full runs passed **15 processing tests on each emulator**: original processing invariants, original benchmark, RC3 challenge/quality/parity/latency, resource measurement and temporal metrics/arithmetic. The additional normalized-fallback cancellation test then passed separately on both emulators, bringing verification to **16 distinct processing tests per emulator**. No production behavior changed between these runs.

Previous held-out geometry results are unchanged on both emulators: original benchmark **10/10 documents, 0/2 false positives, IoU 0.99628**; RC3 challenge **5/6 documents, 0/4 false positives, IoU 0.83086**. The original regression thresholds remain satisfied, including matched corner error <=0.005 and emulator p95 <=100 ms. The previous difficult narrow receipt remains a miss.

## Limits and next validation

This detector identifies supported quadrilateral boundaries, not document semantics. It cannot recover genuinely unobserved sides, flatten curved pages or reliably distinguish all rectangular shadows/screens/objects from paper. Faint shadowed boundaries can still disappear. Automatic capture must use raw focus, brightness, geometry and stability; improved outline recall is not permission to capture a blurred frame.

Synthetic affine transforms use zero-filled overscan in a few outer-border pixels, which can inflate whole-frame Laplacian sharpness. This cohort therefore cannot establish focus or automatic-capture acceptance. Nor do two emulator runs establish real camera jitter, arm64 performance, sustained thermal behavior or duplicate-capture correctness. Next acceptance requires physical handheld footage with paper/background similarity, shadows crossing different sides, several light levels and natural small motion, with independent frame/crop truth and shutter outcomes. Do not advertise the handheld problem as solved from these measurements alone.

## Changed files and evidence

Changes are limited to `OpenCvProcessor.kt`, module `README.md`, new `TemporalShadowBenchmarkTest.kt`, and `src/androidTest/assets/temporal-shadow/` (112 PNGs plus manifest), together with this report. Camera/UI behavior has separate ownership. Existing cohorts, gates, native binaries, AI license evaluation and RC3 release artifacts were not changed.

Evidence: [RC3 temporal baseline](../evidence/rc4-temporal-baseline.json), [RC4 API 35 temporal output](../evidence/rc4-processing-api35-files/temporal-shadow-results.json), [RC4 16 KB temporal output](../evidence/rc4-processing-16k-files/temporal-shadow-results.json), [original regression](../evidence/rc4-processing-api35-files/benchmark-results.json), [RC3 challenge regression](../evidence/rc4-processing-api35-files/detection-challenge-results.json), [repeated latency](../evidence/rc4-processing-api35-files/detection-latency-results.json), [API 35 full test output](../evidence/rc4-processing-api35.txt), and [16 KB full test output](../evidence/rc4-processing-16k.txt), [API 35 fallback-cancellation output](../evidence/rc4-processing-cancellation-api35.txt), and [16 KB fallback-cancellation output](../evidence/rc4-processing-cancellation-16k.txt).
