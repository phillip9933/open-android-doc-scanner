# Native detail and accessible test exports — 0.1.0-rc12-quality2

Historical tuning record. The published successor is [RC12](RELEASE-RC12.md); candidate-only statements below describe that earlier stage.

Local, unpublished candidate. Builds on the uncommitted quality1 changes; no Raiun integration or remote publication is included.

## Pixel workflow

1. Install `scanner-sample-0.1.0-rc12-quality2.apk` over the previous test app.
2. Capture/import, confirm the crop, tap **Next**, select JPEG or PDF and **Save**.
3. The result screen saves JPEG/PNG exports to **Pictures/Offline Scanner** and PDF exports to **Download/Offline Scanner**. Open Files and browse Images/Downloads, or use the result screen's **Open**/**Share** buttons. Images are also discoverable in the gallery's Offline Scanner device album; cloud backup depends on your gallery settings.
4. Each page shows **Capture** and **Export** pixel dimensions. Choose **Inspect original** or **Inspect export**, then **100% detail**. Move the horizontal/vertical sliders to inspect small text. One image pixel maps to one device pixel; Overview is deliberately smaller.
5. **Share original** shares a byte-for-byte copy of the untouched capture, including its metadata. For PDFs, Inspect export uses the exact JPEG page embedded in the PDF. Send the original and export together when reporting softness.
6. The last completed scan can be reopened after restarting the sample. Public copies survive sample uninstallation; private original/inspection copies do not. Starting another scan does not delete earlier public exports.

The SDK review screen explicitly labels its up-to-1024-pixel preview in sample diagnostic mode. Judge sharpness in the result screen's 100% view or saved image, not by enlarging that preview.

On Android 26–28, public saving uses **Save to folder** and the system document picker rather than requesting broad storage access. Automatic Pictures/Download saving requires Android 10+. The Pixel target follows the automatic path.

## SDK changes and limits

- `ScanConfig.maxOutputDimension` now defaults to 8192. The appended `maxRenderPixels` (24,000,000) and `maxWorkingBytes` (384,000,000) settings replace the fixed 8 MP output cap and 4096-pixel export decode edge.
- Decode/output pixel allowance is the smaller of `maxRenderPixels` and `maxWorkingBytes / 16`. The byte setting is an **allocation estimate, not an enforced total-process memory ceiling**. OpenCV/codecs, concurrent jobs and host allocations can add memory; target-device peak/RSS and thermal acceptance remain necessary. Hosts can lower the budget explicitly. Low-memory failures remain typed errors.
- Typical 12 MP inputs retain native resolution when the crop permits. Full-frame Original skips unnecessary perspective interpolation and image copies. Processing never enlarges the crop to reach a configured limit. Small preview jobs retain smaller decode budgets. JPEG exact decode applies on API28+; other formats/older Android retain conservative sampling.
- PDF processing stays sequential and page resolution remains independent of page count.
- `ScannerFlow(includeDiagnostics = true)` forwards the opt-in to `AndroidExporter.export`. `ScanOutput.diagnostics` contains retained `originals` and `renderedPages`. These are copied inside the export staging directory and committed with the output. Cancellation/failure removes staging. The normal default is false, preserving existing temporary-original cleanup for hosts.
- The sample opts in and keeps its private originals for comparison. SDK hosts remain responsible for consuming and deleting committed outputs and diagnostic copies. Raiun should keep diagnostics disabled in ordinary/encrypted production flows unless an explicit diagnostic feature is wanted.

## Verification

Passed on API35 x86_64: 35 processing tests, 8 exporter tests (including diagnostic original-byte preservation, exact PDF page JPEGs and cancellation cleanup), and 14 sample tests (including MediaStore metadata/bytes, failure rollback, result-screen inspection, all-eight-EXIF geometry, and existing camera/UI flows). Five core and 63 camera JVM tests passed. Sample assemble/lint and an independent local-Maven consumer build passed. The actual result screen is additionally tested after activity recreation; its public-copy journal prevents duplicate saves on retry.

The 4032×3024 EXIF6 fixture now exports **3024×4032**, preserving all 12,192,768 pixels. A configured 4 MP/64 MB job exports 1732×2309 with a warning. The 12 MP resource run returned native dimensions for every filter; approximate sampled native heap peaks were **106–165 MiB**, and render times **332–1029 ms** on this emulator. The sample diagnostic viewer decodes only a viewport-sized region for 100% detail; it does not allocate a full-resolution bitmap for inspection. Tests use generated content, not the user's document. Synthetic/emulator checks do not establish handheld Pixel focus quality, camera output resolution, or Google Drive parity.

## Local integration

All five modules use **0.1.0-rc12-quality2**, with `dev.offlinescan:scanner-ui-compose:0.1.0-rc12-quality2` as the flow entry point. Recompile the host against this version; do not mix modules from quality1/RC11. The Maven archive is a local repository, not a remote release. Standalone app storage/sharing behavior is sample-only. Raiun integration still requires ordinary and encrypted destination acceptance and unchanged host staging cleanup.
