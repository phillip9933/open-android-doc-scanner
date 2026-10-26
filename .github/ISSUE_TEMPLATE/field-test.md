---
name: Scanner field-test report
about: Report a capture, crop or OCR result from a release candidate
labels: type:bug
---

## Goal or problem
What failed, and what did you expect? For numbers, describe a substitution without posting the full identifier.

## Candidate and environment
- SDK version / source commit:
- Host app version, device and Android version:
- Capture/import, Standard/AI detection, automatic/manual crop:
- OCR language, document/sparse/single-line, selected region, straightening on/off:
- Elapsed time (if shown):

## Reproduction and result
- Printed/screen/handwritten content; English/Japanese/mixed:
- Card front/back or document; small/rounded/low contrast/glare/blur/tilt:
- Automatic crop correct before manual edits? Content clipped? Internal box selected?
- Expected vs actual (use fictional or non-identifying text):
- Repeatable across captures? Better after manual crop?

Do not attach private scans, names, identifiers, addresses, barcodes or full OCR dumps. A synthetic example is welcome but optional. Note if redaction changed the image.

## Scope and dependencies
What workflow is affected? Anything explicitly excluded?

## Done when
- [ ] Maintainer reproduces or records enough evidence to assess the failure.
- [ ] Fix or explicitly accepted limitation is documented.
- [ ] Verification results linked before closing as completed.

## Verification
Maintainer: record tested build/settings, results and regressions here.
