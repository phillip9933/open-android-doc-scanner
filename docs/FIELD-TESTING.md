# Scanner 0.8 testing and rollout

Publish the combined candidate after internal checks. On 26 October 2026 the owner authorized 0.8.0-beta.1 publication using synthetic/emulator/build verification and opt-in real-world reports from integrating apps. A public prerelease is for collecting broader evidence, not a declaration that accuracy acceptance passed. Keep 0.7.0 as stable until the release gates are met. Do not distribute the development APK as the SDK product.

## Internal candidate gate

- All relevant JVM, instrumentation, packaging, license/native and independent-consumer checks pass on the frozen candidate.
- Original images survive cancellation, failure and interruption; failed outputs do not replace successful ones.
- Capture, automatic boundary proposal, perspective crop, manual corner correction, export and optional OCR work together.
- Validate card fronts/backs, small/rounded cards, low contrast, tilted cards, glare and blur. Check external card edges rather than internal text boxes. Record missed boundaries, clipped content, wrong-surface proposals and required manual corrections separately.
- Validate English and Japanese, mixed scripts, names, leading-zero numeric strings, and boxed forms. Record exact-field matches and character errors; average confidence is not proof of correctness.
- Optional line straightening must preserve full-image word coordinates, budgets and cancellation. Test both enabled and disabled, blank/short/multiline inputs and boundaries. Do not apply it silently to full pages.
- Record device/OS, ABI/page size, source commit, models, settings, elapsed time and memory. An unavailable phone leaves device acceptance pending; emulator results cannot stand in for photographs.

## Repeatable tester sessions

Use a short fixed set first: one English page, one Japanese page, one mixed-script card front/back with fictional printed name and number (including a leading zero), one low-contrast card, one mild-tilt card, and one glare/blur challenge. Take two captures of each. Record whether the default crop was correct before touching corners. Then correct it and compare OCR, so detection and recognition failures remain distinguishable.

After the fixed set, users may test their own normal documents locally. Do not request private originals, names, identifiers, addresses, barcodes or full OCR dumps. A useful report can say “second digit 8 became 3” and include the settings/timing. Synthetic or safely redacted examples are optional. Redaction can change recognition, so label such examples accordingly. No automatic uploads or telemetry are added.

Use the repository's **Scanner field-test report** issue form. Reports stay Inbox until triaged. Confirmed defects get the appropriate priority, reproducible expected/actual behavior, and a release target only when accepted into scope. A single bad number matters even when aggregate character accuracy is high. Do not silently repair numbers or infer a name from expected values.

## Promotion decision

Review coverage across multiple testers/devices and actual reported failure rates, rather than waiting a fixed number of days. Agree and record acceptable limitations before RC; never mark untested cells passed. Data loss, crashes in core workflows, clipped originals/exports, or misleading automatic acceptance block promotion. OCR outputs remain editable and require host confirmation, especially identifiers. Known quality gaps can remain documented limitations only after an explicit scope decision.

Freeze scope at RC and rerun affected checks on any change. After owner approval, publish one SDK release with immutable source, six-module Maven archive, hashes, licenses, model instructions and notes. Kura/Raiun integrations consume the published version; host field mapping and save confirmation stay in their repositories.
