# Scanner SDK 1.0.0

## Page detection

In the ready-made scanner, AI gets the first chance to find the page. If it misses on two consecutive frames of a similar scene, Standard checks the edges on the second frame. A successful fallback is checked afresh on subsequent missed frames. Captured images, imports and an explicit automatic crop get one immediate edge check after an AI miss. Standard mode remains available in settings. Automatic capture still requires a steady page and a passing focus check.

In an emulator replay of 78 held-out synthetic scenes, the scanner drew a correct outline for 43 of 48 pages, compared with 1 using AI alone. The 42 recovered pages appeared in about 207 ms at the median. Neither path drew an outline on the 30 no-page scenes. Both retained one incorrect outline on a positive scene. Each scene supplied three identical frames 180 ms apart. Correctness required a visible outline covering at least 75% intersection over union with the reference. These measurements cover warmed detection on static inputs.

## Upgrading from 0.8.0

Use version `1.0.0` for all six Maven modules you consume and recompile the app and any prebuilt wrappers that use `OcrConfig`. The configuration gained a working-memory budget. Android API 26+, arm64-v8a and x86_64 remain supported. Apps using offline English or Japanese OCR continue to supply the pinned language models.

- Selecting a small OCR field preserves its detail within the memory budget. Word boxes use the full rendered image coordinates.
- The crop editor and export agree on mirrored image orientation. Manual capture uses the mode selected when the shutter is pressed.
- A cancelled render preserves an existing destination file. Changing the host scan configuration starts a fresh session.
- Preview rendering, OCR decoding and document-provider transfers have resource limits. The SDK archive includes dependency inventories from the audited build.

A glossy card, a shadow or a rectangular background object can still lead to a missed or incorrect boundary. Users can adjust the crop and review recognized names and numbers before saving.
