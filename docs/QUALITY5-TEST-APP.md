# Quality5: local background cleanup

Historical tuning record. The published successor is [RC12](RELEASE-RC12.md); candidate-only statements below describe that earlier stage.

Local candidate `0.1.0-rc12-quality5`, sample version code 16. Keeps quality4's channel-wise paper normalization and ink tone curve, and quality3's detailed preview.

Auto uses a smaller neighborhood for the low-resolution paper estimate (7 instead of 17 samples, with less smoothing). This lets the estimate follow broad stains and edge shadows more closely instead of treating them entirely as foreground. A smooth near-paper suppression curve reduces weak neutral reverse-side lines, fading out at 40 levels below the normalized paper. Colored marks bypass this neutral suppression, and clear ink uses the same tone curve as quality4.

The tradeoff remains appearance-based: very faint genuine marks can resemble bleed-through, and large colored areas can influence the paper estimate. Original remains available for preserving those features. Strong stains and reverse-side printing may remain; this is not semantic foreground/background separation.

Synthetic coverage adds a top-edge gradient shadow, broad yellow stain, weak reverse-side line, pencil stroke and dark print. Existing checks cover cyan paper, red and blue annotations, pale writing, original preservation, cancellation and preview/export consistency. No private document fixtures are included. Physical Pixel acceptance is still needed.

Validation: 36 processing instrumentation tests passed on API 35, with two optional detector benchmarks skipped. Processing lint, sample APK build and local packaging of all five SDK modules passed. The new synthetic test verifies reduced edge shadow and yellow cast, reverse-line contrast below 15 levels, pencil contrast above 35 levels, and dark print below 85. These thresholds are fixture results, not measurements of the user's photos.
