# 0.1.0-rc12 — document quality and accessible exports

RC12 incorporates the accepted quality5 processing and publishes all five SDK modules plus a standalone debug-signed test APK. It improves small-text detail, background cleanup and the ability to judge and locate exported scans.

## Changes

- Render defaults: 8192-pixel maximum edge and 24MP pixel budget, additionally bounded by a configurable 384MB working-memory estimate. Typical 12MP captures retain native detail. These are allocation estimates, not a hard process-memory ceiling; returned dimensions/warnings report limiting.
- Auto normalizes local RGB paper color to reduce cyan casts, shadows and broad stains, retains dark text, and gently suppresses weak neutral bleed-through. Faint writing and colored-mark regressions are covered. Strong bleed-through can remain; Original and manual filters remain available.
- PDF streams one processed JPEG page at a time, preserves per-page resolution independently of page count and uses a 300-DPI physical page size. Originals remain unchanged; cancellation cleans incomplete outputs.
- Selected review, save and appearance previews render up to 3072 pixels with direct bitmap zoom; stable thumbnails use a separate smaller cache. This is still a preview, not a guarantee of full export detail.
- Manual capture reports quality warnings without discarding the user's photo; focus and still-image checks complement automatic capture.
- Sample exports JPEG to Pictures/Offline Scanner and PDF to Download/Offline Scanner on API29+. API26–28 uses the system document picker. The sample reopens its last completed result, shows capture/export dimensions and offers original sharing and 100% detail inspection.

## SDK integration

Consume `dev.offlinescan:scanner-ui-compose:0.1.0-rc12` from the unpacked Maven archive. Add processing/export coordinates explicitly when importing their APIs. Hosts retain output ownership and storage policy; the public-folder workflow belongs to the sample.

`ScanConfig` adds `maxRenderPixels` and `maxWorkingBytes`. `ScannerFlow` and `AndroidExporter` add optional `includeDiagnostics=false`; opt-in `ScanOutput.diagnostics` retains original copies and rendered page files after session cleanup. Hosts enabling it must include those files in their retention/deletion policy. Recompile consumers when upgrading: the new Kotlin parameters/data-class fields are not a binary-compatibility promise. Existing named/trailing-lambda examples remain supported; positional ScannerFlow callbacks may need conversion to named `onResult`.

No native-library, model-weight or runtime dependency change. License and native provenance notices remain included. No INTERNET permission or remote processing is added.

## Validation and limits

Quality5 processing passed 36 API35 instrumentation tests with two optional detector benchmarks skipped. Quality3's UI passed 14 tests; quality2's exporter passed eight. Final RC12 build, audit and regression results are recorded below before publication. Synthetic results do not establish Google parity or broad device acceptance. The user accepted the quality5 sample after Pixel testing; private samples are not distributed.

Physical API26/arm64/16KB, TalkBack, sustained thermal and host integration acceptance remain incomplete. Very faint foreground marks can resemble bleed-through; intentionally colored paper may be neutralized in Auto. Original is available when fidelity takes precedence over paper cleanup.

AI assistance was used for implementation, analysis, tests and documentation; validation statements refer to observed tool results.

Final RC12 validation (API35 x86_64): 68 JVM tests; 36 processing tests passed with two optional diagnostics skipped; eight exporter and 14 sample/UI tests passed. All-module lint, final APK/dependency/native audit, eight audit self-tests, five local Maven publications and independent Maven consumer assembly passed. See [validation summary](../evidence/rc12-validation.json) and [APK audit](../evidence/release-audit.json). No new physical API26/arm64/16KB run was performed for RC12.
