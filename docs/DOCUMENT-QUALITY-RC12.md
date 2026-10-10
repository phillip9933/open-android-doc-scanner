# Document quality local candidate — 0.1.0-rc12-quality1

Historical tuning record. The published successor is [RC12](RELEASE-RC12.md); candidate-only statements below describe that earlier stage.

Unpublished SDK and standalone debug candidate, 2026-10-10. This work starts from `5dc1f31eb380f28399a96197b4488f9beca95344`; the processing and exporter files at that checkout match `5819098aec9387c4f823b7cebfec20ff0c782610`. No Raiun source or upload/encryption behavior is changed.

## Behavior and tradeoffs

- Processing retains a typical 4032×3024 capture through perspective correction. Source decoding remains bounded (16 MP, 4096-pixel edge), and the crop/output remains bounded to 8 MP and `maxOutputDimension`. An 8 MP A4-shaped image is about 289 DPI; smaller captures/crops cannot gain detail by upscaling. Preview requests use smaller decode budgets. Intermediate bitmaps and Mats are released promptly. Larger native allocations still require real-device memory acceptance.
- Android 28+ JPEG export decoding uses an exact target size. Android 26–27 and non-JPEG imports retain conservative power-of-two decode fallback with explicit EXIF transforms. Original files remain untouched; edits re-render from the original.
- Auto keeps continuous grayscale or color for ordinary documents. Paper correction uses bounded illumination lift rather than contrast amplification that can clip pale ink. Ambiguous photos/blank scenes retain Original. Manual Black and white normalizes broad shading and scales its adaptive neighborhood with output resolution. It remains a lossy interpretation; use Auto/Grayscale or Original for pale signatures, stamps and highlights if binary output loses marks.
- PDF writes processed JPEG bytes directly into one image object per page. It does not decode the JPEG again or retain all page rasters. Every page receives the same processing configuration regardless of page count. JPEG uses the configured quality (default 92), once. PDF physical dimensions now use 300 pixels per inch instead of one pixel per PDF point; `ExportedPage` continues to report pixels. PDF remains image-only, without OCR.
- Manual document capture checks focus results and measured original-still sharpness, shows a short review/retake hint for likely softness, and keeps the page. Unsupported focus and unmeasurable/blank images do not prevent capture. No sharpening disguises focus errors.

## Validation

Passed: 5 core JVM tests, 63 camera JVM tests, 35 processing tests and 6 PDF/export tests on API35 x86_64, plus 16 processing/enhancement compatibility tests on API26 x86_64. Sample debug build and lint passed. Two opt-in learned-model diagnostics are excluded from the processing run. All 8 existing end-to-end sample UI tests passed on the final API35 build (including manual/automatic capture, crop/review/PDF and lifecycle cleanup). An independent Android consumer compiled successfully against the locally packaged Maven artifacts; low-level processing/export APIs require their explicit module dependencies. See supplied run logs. These checks are not handheld Pixel, sustained arm64 memory/thermal, or Google Drive parity acceptance. The primary source of the owner's softness has not been measured on their original captures.

### Measured synthetic results

- 4032×3024 JPEG with EXIF 6: upright decode/crop 3024×4032, exported raster **2449×3265** (7,995,985 pixels). The explicitly simulated previous sample=2 path exports 1512×2016 (3,048,192 pixels). The simulation is labeled; it is not an archived RC11 executable run.
- Broad-shadow Auto fixture: paper 150 → 195; ink 106 after processing; lit paper 242. Shaded ink/paper contrast remains 89 levels. Source hash is unchanged. Conservative normalization leaves residual shadow rather than forcing all paper to white.
- Same printed source through JPEG and PDF: sampled RGB mean absolute error **0.0** at the rendered image resolution in the emulator. Single- and three-page tests check every embedded page's dimensions; twelve 3000×3000 pages exercise streamed writing. Progress-boundary native heap varies by only a few KiB; that measurement does not include per-page processing peaks.
- Full 12.19 MP processing sampled every 2 ms: native heap approximately **153–176 MiB**, with render times **307–764 ms** on the API35 x86_64 emulator. These are sampled process-heap peaks, not exact RSS or Pixel estimates. Output remains bounded to 8 MP; no aggregate page raster is kept in memory.
- The same faint-print input is evaluated with the previous 31/C=12 Gaussian threshold, corrected scale-aware Gaussian, and a test-only Sauvola comparator (window 45, k=0.20, R=128). Both newer candidates retain text in this diagnostic. An additional one-pixel, ~12-level-contrast line retained **680/680 sampled pixels** with corrected Gaussian versus **4/680** with the previous fixed threshold; this targeted test passed separately after the full suite. Gaussian remains the implementation; this small synthetic comparison does not establish universal superiority over Sauvola. Auto continuous tones are the safe default.

Generated evidence is in `outputs/document-quality/final-api35/` at the project-container level, with exporter evidence and run logs alongside it. `tools/compare_document_quality.py` creates a labeled comparison from pulled test files. Synthetic fixtures include text/numbers/punctuation, pale rules and text, color stamps and existing colored-form/highlight/illustration, shadow and light/dark-background cases. Readability checks are pixel/contrast and geometry checks, not OCR-only scores.

## Pixel test

1. Install the supplied debug APK with `adb install -r scanner-sample-0.1.0-rc12-quality1.apk` (replacing an existing differently signed sample may require uninstalling that sample first).
2. Photograph the same printed A4/Letter sheet under even light and a broad side shadow. Include small numbers/punctuation, faint gray text, thin table rules, a signature, a colored stamp and a highlight. Fill the frame while leaving room to confirm the crop.
3. Keep each original capture and compare Original, Auto, Grayscale and Black and white at the same zoom. Check punctuation, pale strokes and line continuity, not just how white the paper looks. Reuse that capture for JPEG and PDF comparisons.
4. Export one page, then the same page in a 10-page PDF. At equal image scale, text should retain identical resolution. Verify crop/rotation, page order, cancel during export, and failed destination handling.
5. Try deliberate slight motion, failed/unsupported autofocus and a blank page. Soft manual captures should remain available with review guidance; a sharp capture should not need repeated attempts.
6. Record device/Android version, capture dimensions, returned export dimensions, duration, app memory and any crash/slowdown. Compare handheld Auto and manual results with Google Drive using the same sheet and lighting; those are separate real-camera captures, not an identical-source algorithm comparison.

## Local artifacts

- SDK Maven archive: `outputs/document-quality/open-android-doc-scanner-0.1.0-rc12-quality1-sdk-maven.zip`.
- Standalone debug APK: `outputs/document-quality/scanner-sample-0.1.0-rc12-quality1.apk`.
- `outputs/document-quality/SHA256SUMS` identifies the exact delivered bytes.
- Unzip the SDK archive and add its `maven/` directory to the host's Gradle repositories. This is local packaging only; nothing was committed, pushed or published remotely.

## Raiun handover

Consume all five modules at `0.1.0-rc12-quality1` from the supplied local Maven archive (entry point `dev.offlinescan:scanner-ui-compose:0.1.0-rc12-quality1`). Do not mix RC11 transitive modules. This version is not on a public Maven repository.

After SDK acceptance, validate Raiun's ordinary and encrypted destinations with both JPEG and multipage PDF. Verify the bytes uploaded are the exported bytes, cancellation/failure removes host-owned staging, originals survive until edits/export complete, and SDK session cleanup does not remove committed output before the host has consumed it. Keep existing encrypted-destination cleanup and key handling unchanged. Raiun integration is not included in this SDK change.
