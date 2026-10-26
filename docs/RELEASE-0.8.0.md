# 0.8.0-beta.1 — offline OCR and card scanning

This beta is for apps integrating the scanner SDK. Stable remains 0.7.0. The owner authorized beta publication on 26 October 2026 using synthetic, emulator and build verification, with broader real-world reports gathered through integrating apps. This is not stable acceptance or a claim of Google-equivalent recognition.

## Changes

- Optional offline English/Japanese OCR through `scanner-ocr-tesseract`, with text, confidence and word positions. Hosts provide the pinned local models; the SDK performs no model downloads.
- Selected single-line straightening (opt-in), with bounded processing and full-image word coordinates. This targets tilted printed fields; it does not guess or correct characters.
- Ordered field OCR for up to 32 host/user-defined regions. IDs, raw text and leading zeros are preserved; the SDK does not infer card fields.
- Color-edge detection and a supported enclosing-boundary preference to avoid selecting an internal text panel instead of a card.
- Developer sample field selection, numeric-line diagnostics and visible OCR settings. The sample APK is not a public SDK release asset.
- Native/model integrity checks, private model snapshots and strict dependency verification. See [OCR security](OCR-SECURITY.md).

## Integration

All six Maven modules use `0.8.0-beta.1`. Extract the release SDK Maven ZIP and configure its `maven` directory as a repository. Update modules together and recompile consumers. OCR is opt-in; plain scanner consumers need not add the OCR module. Android API 26+, arm64-v8a and x86_64 are unchanged. See [OCR integration](OCR.md), [general integration](INTEGRATION.md) and bundled license/native provenance.

## Verification and limitations

Local JVM tests, lint, SDK assembly, archive checks and independent plain/OCR consumer compilation pass. Emulator evidence includes five OCR and 39 processing cases on API 37.2/x86_64/16 KB, covering a tilted numeric line, coordinate mapping and small/rounded/tilted/low-grayscale-contrast cards. The ordered-field helper is covered by core tests and archived-Maven consumer compilation. Earlier device evidence is retained separately; no new physical-phone testing was used for this beta.

Synthetic host numeric experiments improved from 51/84 exact cases to 78/84 with line straightening. These host results are not device accuracy guarantees. Known-field Japanese-form experiments found all fields in 10/12 scenarios; supplied rectangles/order are not automatic form parsing.

Known limitations: incorrect digits/Japanese characters, imperfect boxed-form reading order, missed or wrong crop proposals, glare/blur and ambiguous backgrounds. Real-camera detection latency/thermal impact and broader accuracy remain under evaluation. OCR confidence is not validation. Hosts must let users inspect/edit identifiers and names before saving. Original scans remain unchanged. Native OCR cannot be interrupted mid-call; cancellation is observed around it.

Searchable PDF, automatic card-field interpretation, simultaneous multi-document capture, handwriting guarantees and cloud OCR are not included. No stable promotion is implied. Report non-private reproductions through the [field-testing process](FIELD-TESTING.md); acceptance remains tracked in issues #2 and #5.

See [candidate validation](../evidence/ocr-card-candidate-validation.json), [OCR acceptance](OCR-ACCEPTANCE.md) and [card/form investigation](OCR-CARD-INVESTIGATION.md). The release contains the SDK Maven ZIP and SHA256SUMS, with GitHub-generated source archives; no standalone APK.
