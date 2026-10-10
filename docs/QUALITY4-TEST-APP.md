# Quality4: neutral paper and darker ink

Historical tuning record. The published successor is [RC12](RELEASE-RC12.md); candidate-only statements below describe that earlier stage.

Local candidate `0.1.0-rc12-quality4`, sample version code 15. Retains the [quality3 detailed review](QUALITY3-TEST-APP.md) and [quality2 export workflow](QUALITY2-TEST-APP.md).

Auto estimates the local paper background separately in RGB, then divides each source channel by that estimate with a near-white target of 252. Unlike quality3's additive correction, this preserves black rather than lifting it along with the paper. A gradual tone curve darkens clear ink; the near-paper range stays continuous. Weak neutral show-through suppression remains soft and fades out for stronger strokes and colored annotations.

This addresses the observed cyan paper and washed-out letters. It is not a semantic separation of ink, paper, stains and reverse-side printing. Intentionally colored paper can be neutralized; Original is available when its color should be retained. Strong bleed-through or stains may remain. Pixel hardware acceptance is still needed.

Synthetic regression includes cyan paper with dark print and red annotations, broad shadows, faint ink, pale blue marks, weak show-through, unchanged originals, and preview/export agreement. User documents are not included in test fixtures.

Validation: 35 processing instrumentation tests passed on API 35; two optional detector benchmarks skipped. Processing lint and the sample APK build passed. All five modules were packaged locally. Cyan-paper test requires RGB spread at most 5, paper channels at least 248, dark text below 80, and red annotation red-green separation above 35. These are synthetic thresholds, not measurements of the user's document.
