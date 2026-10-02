# Remaining physical-device and host acceptance

Use only synthetic fixtures or intentionally supplied nonprivate printed test sheets. No production accounts needed. Record device/OS/ABI/page size, SDK config, fixture IDs, outcomes, elapsed time and peak-memory method. Passing emulator tests alone does not approve the release.

## Physical camera (arm64)

- API 26 oldest supported device and modern API 35/36 arm64 device, including a real 16 KB page-size device where available. Install release host build; verify native load/capture/export. Emulator evidence only covers x86_64 execution.
- Rear camera portrait/landscape and display rotations 0/90/180/270. Compare live outline with full-resolution captured crop on a printed corner grid. Check CameraX shared-viewport crop, letterboxing and lens sensor orientation. No front-camera mirror support is promised.
- Document on light/dark background; tall receipt; card front/back; colored form with faint gray text, handwriting, stamp and highlight; printed photo with fine texture and saturated colors.
- Shadow, dim light, strong glare, blurred/moving image, partly out-of-frame paper and clutter without a page. Verify honest failed detection/manual recovery and shutter access even when auto gates reject.
- At least 30 static-page trials: measure duplicate/missed automatic captures; remove/swap page and reframe to exercise scene-change lockout. Gate: zero duplicates while page remains still, >=90% timely captures on eligible stable scenes, no auto capture without valid boundary. These are provisional physical gates to review against measured baseline, not claims already satisfied.
- Measure live analysis p95 <=100ms and bounded worker/backpressure; render 5.76MP page p95 <=2s on target midrange hardware, memory <=256 MiB process PSS under representative 10-page sessions. Tune resource limits rather than weakening correctness gates if targets fail. Monitor camera thermal/sustained behavior and background/foreground transitions for five minutes.

## Output and fidelity

- Compare original/PHOTO at 100%: no invented text, erasure, thresholding, or document whitening by default. Inspect clipped edges and small marks after manually verified crop; exact clipping baseline must be measured on real printed sheets.
- Confirm JPEG/PNG orientation and dimensions with independent viewers; image PDF page count/order and visual fidelity; GPS/make/model stripped. Verify host copies/retains outputs before deleting temporary host files.
- Import extremely large/corrupt image and reach page/resource limits. Cancel during render/PDF and verify only that operation's pending files disappear. Existing host originals and other successful scans remain.

## UX and lifecycle

- Deny/revoke camera permission: import and manual crop remain available. Disable Google Play services and network before first launch. No licensing/API-key prompt.
- Light/dark, 200% and maximum supported fonts, TalkBack corner sliders with distinct horizontal/vertical labels, keyboard/switch navigation, controls >=48dp. Crop magnifier accuracy near corners and image edges.
- Reorder/replace/retake/remove pages; rotate and reset crop/appearance; compare original; cancel and relaunch. The default flow contains no library/history.
- Rotate activity, background/foreground, kill process during import/capture/render/export. Active session is deliberately discarded. Follow the stale-session cleanup rule in ARCHITECTURE.md; delete `.pending-*` export dirs only while no export operation is active. Completed outputs remain host-owned.

## Hosts

Build and run OpenCloud and Kura with the local Maven artifacts, without modifying their unrelated logic. See INTEGRATION.md for exact acceptance steps. Their account/storage/navigation integration and acceptance remain unverified until separately authorized in those repositories.
