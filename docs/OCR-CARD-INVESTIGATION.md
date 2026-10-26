# Card OCR investigation — 26 October 2026

Real-camera acceptance remains open in issue #2. Local inspection of an owner-provided card showed Japanese substitutions and spurious text; the owner also reported an incorrect card number. The number and its source were not both visible, so the exact digit substitution has not been established. No private image or extracted text is included here.

## Reproducible diagnostic

`tools/experiments/card_ocr.py --models <pinned-model-directory> --output <local-output>` generates a fictional bilingual card, a card on a larger page, a number crop and light-on-color text crops. It verifies the same English/Japanese model hashes used by the SDK. It requires Pillow, Noto Sans CJK and host Tesseract; it never reads personal images or accesses the network.

On host Tesseract 5.5.3, both document (PSM 3) and sparse (PSM 11) modes recognized `0012345678` in the full card and page fixtures with English, Japanese and bilingual models. The isolated number was exact in all nine language/layout combinations. Full-card single-line mode failed, as expected for an inappropriate layout. Inverting the light-on-color crop produced no gain on this fixture. These clean fixtures therefore **do not reproduce or resolve the photographed-card failure**. Host timings are not Android performance evidence.

## Test app support

The OCR screen now allows dragging a rectangle around text or a number in the saved upright image. Recognition uses the existing SDK `OcrConfig.region`; returned word coordinates remain relative to the full image. Selecting or clearing a region discards stale results. A digits-only option is available only with a selected region and uses the English model, single-line layout and numeric whitelist. This is for an explicitly selected printed number, not an entire mixed-language card. Leading zeros remain strings. A whitelist cannot validate or repair an incorrect digit.

Results display language, layout and region settings to make comparisons reproducible. The original scan is unchanged. Automatic card detection, inference of field meaning and barcode decoding are not added by this diagnostic UI.

## Remaining evidence

Compare whole-card versus selected-field recognition on the same photographed raster, with the printed number visible at 100%. Record selected language/layout and elapsed time. Use a synthetic reproduction of the failing typography, scale or background before changing engine preprocessing. Retest English/Japanese documents and cards on Android. Do not declare acceptance or publish a stable release based on the clean host fixture.

## Validation of the diagnostic update

Debug app build, test compilation and Android lint pass. Pixel 10 Pro instrumentation passes the OCR UI case including region drag, confirmation, stale-result clearing and whole-page reset (2.366 seconds for the complete test). An initial attempt while the phone was locked could not find the Compose hierarchy; the test passed after the owner unlocked it. This validates the controls, not accuracy on the owner's card. The updated debug app was installed without clearing app data.


## Small-text and blur follow-up

The extended matrix contains 117 host runs, including paired 700 px and 350 px cards, Gaussian blur radius 0.6, and enlargement of the same degraded pixels back to 1400 × 850. In the four Japanese/bilingual document/sparse configurations, the 350 px unblurred card's light-on-color Japanese sentence was exact in 0/4 runs before enlargement and 4/4 after. With blur, neither original nor enlarged pixels recovered that sentence (0/4 each). Numeric recognition remained exact in all these document/sparse cases. See `evidence/ocr-card-synthetic.json` for the scoped summary.

This supports investigating an explicit small-text enlargement option, but does not justify a default change: the host engine differs from the Android build, enlargement costs memory, and it did not repair lost detail or reproduce the owner's wrong number. No automatic preprocessing or synthetic replacement of recognized characters is introduced by this update.
