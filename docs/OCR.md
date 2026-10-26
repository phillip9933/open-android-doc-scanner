# Offline OCR and card integration

The unreleased 0.8.0 update adds an optional `scanner-ocr-tesseract` module. Existing scanning apps do not load OCR or language models unless they add this module. `scanner-core` provides the engine-neutral contracts so hosts can also use another recognizer.

## Dependencies and models

Add `dev.offlinescan:scanner-ocr-tesseract:<SDK version>` alongside the scanner modules. Until 0.8.0 is released, use the local Maven publication or a source build. Do not add the original Tesseract4Android dependency alongside this module: the Java/JNI class names overlap. No JitPack repository is needed: the module includes rebuilt native libraries and attributed Java/JNI bindings. The build uses Tesseract 5.5.3 with two explicit model-parser fixes, libpng 1.6.59, Leptonica 1.85.0 and libjpeg 9f. See [native rebuild and security evidence](OCR-SECURITY.md).

Package the approved `eng.traineddata` and `jpn.traineddata` files from [tessdata_fast revision 87416418](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd) with the host app and copy them to a private `filesDir/ocr/tessdata/` directory. Pass its parent `filesDir/ocr` to the recognizer. Install atomically before workers begin. Retain the model license. No user setup or network connection is needed when the host bundles these files.

The adapter accepts only these approved English/Japanese model hashes. Missing, modified, different-version or unapproved models fail before native initialization. Each job hashes a bounded private copy of its models and input image, and the engine reads those copies to avoid a check/use race. Temporary job copies are deleted on success, exceptions and cancellation; force-stopping the app can leave copies under the private OCR data directory, which the host may remove when no jobs are running. Custom models and `jpn_vert` require a reviewed SDK change or a separate host-provided recognizer.

| Model | SHA-256 |
| --- | --- |
| eng | `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2` |
| jpn | `1f5de9236d2e85f5fdf4b3c500f2d4926f8d9449f28f5394472d9e8d83b91b4d` |

The SDK does not download models. Both languages are enabled by default. The optional engine adds no Google Play services, network permission or telemetry. Models are not included in the SDK AAR; the sample debug app packages the staged test models. `tools/prepare_ocr_test_models.py` fetches and verifies the same approved files for testing.

## Recognize edited scan pages

Call on a worker thread while the session is still open and its originals exist:

```kotlin
val recognizer = TesseractTextRecognizer(File(context.filesDir, "ocr"))
val results = ScanOcr(processor, recognizer).recognize(
    pages = session.pages,
    temporaryRoot = context.cacheDir,
    scanConfig = config,
    ocrConfig = OcrConfig(languages = listOf("eng", "jpn")),
    cancellation = token,
    progress = Progress { done, total -> reportProgress(done, total) }
)
```

`ScanOcr` applies each page's confirmed crop, rotation and filter using the configured processor, writes a temporary lossless PNG, recognizes it, then deletes the temporary raster. Only its own temporary directory is deleted, including on errors/cancellation. Source images and completed exports remain owned by the host/session. Results retain page IDs and input order. A failed page throws; a partially completed list is not returned as success.

For `ScannerFlow`, request JPEG/PNG output and recognize each committed image in `onResult`, or use the processing API above before exporting a PDF. Do not enable diagnostic original copies just to get OCR input. `TextRecognizer` accepts upright raster images, not PDF files; direct input does not apply EXIF rotation. SDK-rendered output has its orientation applied to its pixels.

```kotlin
val text = recognizer.recognize(
    output.files.first(), "front",
    OcrConfig(layout = OcrLayout.SPARSE), token
)
```

`OcrPage` returns full text, rendered width/height, and words with pixel rectangles and engine confidence (0–100). Rectangles are relative to the entire upright rendered image, even when a recognition region is selected. They do not map directly onto the uncropped original. A blank page can correctly return empty text. Confidence is a ranking aid, not a calibrated probability or proof that a name/number is correct.

Native recognition is blocking. Calls on the main thread or overlapping calls across adapter instances are rejected. Cancellation is checked around it and during result collection, so cancellation can take until the current native call returns. Run pages sequentially; `ScanOcr` enforces the configured page limit; do not create an engine per video frame. The input limits reject oversized rasters instead of silently degrading them: default 64 MiB encoded bytes, 24 million pixels, and 16,384 pixels per edge. Model copies are capped at 64 MiB each. Native OCR has additional memory overhead beyond the decoded bitmap. Reduce render resolution for constrained devices; real-camera quality and memory profiling remain required.

## Kura: generic recognition here, field interpretation in the app

Use `ScanMode.CARD` and `cardFrontBack = true` for up to two sides. Card capture already exists; OCR layout is a separate option because a card may contain a paragraph, scattered labels, or a single number. Start with `OcrLayout.SPARSE` for a whole card. For a known field, use `SINGLE_LINE` and a normalized `OcrRegion`:

```kotlin
OcrConfig(
    languages = listOf("eng", "jpn"),
    layout = OcrLayout.SINGLE_LINE,
    region = OcrRegion(left = 0.1, top = 0.55, right = 0.9, bottom = 0.75)
)
```

These coordinates are illustrative, not a universal card template. Kura owns card-type templates, front/back association, names versus issuer labels, number validation, barcode interpretation, duplicate detection and the confirmation screen. Keep identifiers as strings to preserve leading zeros. Do not automatically replace O with 0 or I with 1 without field-specific validation and user review. Use a digit whitelist only when a field is known to contain digits; many card identifiers contain letters.

Recognition depends on typography, language, layout and image quality. Glossy backgrounds and embossed characters can need a different capture angle or filter. Aggressive paper whitening can erase light text on colored cards; allow Original/Photo as well as Auto and evaluate actual card samples. OCR does not decode QR/barcodes, authenticate a card, or infer its fields. Kura should show candidate values with a crop of the source and require correction/confirmation before import.

## Validation and scope

Run:

```sh
python3 tools/prepare_ocr_test_models.py
./gradlew :scanner-core:test :scanner-ocr-tesseract:connectedDebugAndroidTest :scanner-ocr-tesseract:lint :scanner-ocr-tesseract:assembleRelease
```

The explicit model preparation step downloads pinned, SHA-256-checked public English/Japanese test assets into ignored build output. Test APKs use synthetic text only. Models are never included in the release AAR. Unit tests cover page order, edit preservation, model/config validation, file growth during copy, integrity checks, cancellation and temporary-file cleanup; Android tests exercise actual mixed English/Japanese text and a region-limited numeric field. Passing synthetic tests does not establish accuracy on real cards or documents.

Searchable PDF text layers, automatic field extraction, handwriting guarantees, automatic script/orientation detection and live camera OCR are not part of this update.

## Trying OCR in the sample app

Run `python3 tools/prepare_ocr_test_models.py`, then `./gradlew :scanner-sample:assembleDebug`. The script verifies and stages the approved English and Japanese models. Only the sample debug APK and OCR instrumentation APK include these assets; SDK AARs still require host-installed models. The app does not download models.

After saving a scan, select **Recognize text · page N**. Choose English, Japanese, or both, and Document or Card / scattered text. Recognition uses the saved rendered page (including diagnostic raster pages for PDF exports). Results show elapsed recognition time, word highlights, selectable text, and access to the existing 100% image viewer. Changing language or layout clears the old result. Closing the screen discards text; native work already running finishes before cleanup.

Test both languages on documents and card fronts/backs, especially small text, glare, names and numbers with leading zeros. Check text against the image; elapsed time excludes initial model installation. This screen does not extract card fields or add searchable PDF text.
