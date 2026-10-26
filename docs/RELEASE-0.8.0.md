# 0.8.0 — optional OCR (unreleased)

Development version: `0.8.0-SNAPSHOT`. No tag or public release has been created for this update.

- Optional offline Tesseract adapter, with English and Japanese enabled by default when the host supplies both models.
- Plain Kotlin OCR results: page identity, text, word rectangles and confidence.
- Document, sparse-text and single-line layouts, with optional regions and field character constraints.
- Processing integration applies confirmed page edits, preserves order, and cleans temporary rasters on success, failure and cancellation.
- Card capture stays in the scanner; Kura owns field interpretation, validation and import confirmation.

See [OCR integration and test instructions](OCR.md). Searchable PDFs and automatic card-field extraction are not included. Native recognition cannot be interrupted mid-call. Real-camera acceptance remains pending.

Security hardening on 2026-10-24 replaces the upstream OCR AAR with rebuilt Tesseract 5.5.3 plus model-parser fixes and libpng 1.6.59. Approved model hashes are checked on private copies; encoded-file, edge, batch and concurrency limits are enforced. Gradle verifies dependency checksums and CI checks native source/binary hashes. See [security changes](OCR-SECURITY.md).

Final validation: 79 JVM tests, 4 Android API 35 x86_64 OCR/security tests and 8 auditor self-tests passed. Strict dependency verification, lint, OCR release AAR, sample compatibility build and local core/OCR Maven publication passed. Both ABIs meet 16 KB alignment and full RELRO; the tested APK contains the exact patched binaries. See [security validation record](../evidence/ocr-security-validation.json). The [earlier initial OCR record](../evidence/ocr-validation.json) predates hardening and is retained as history.

The sample debug app now offers per-page English/Japanese OCR, document/card layouts, word highlights, selectable results and 100% image inspection. Prepare its pinned models using `tools/prepare_ocr_test_models.py` before building. This is a developer test feature; model distribution in SDK integrations remains host-owned.
