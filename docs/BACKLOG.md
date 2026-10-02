# First-release backlog

Status vocabulary: Not started; In progress; Implemented; Automatically verified; Awaiting device or host-app acceptance; Blocked; Deferred beyond first release. An implemented feature may still need acceptance. No physical-device or host-app acceptance is implied by automated verification.

| Work | Implementation / automatic evidence | Acceptance status |
| --- | --- | --- |
| Platform-free configuration, geometry, typed outcomes and processing contracts | Automatically verified: JVM geometry/session tests | Awaiting host-app acceptance |
| Session imports, immutable originals, edits, page limits, replace/reorder/remove, own-directory cleanup | Automatically verified: JVM ownership/failure tests | Awaiting process-death/storage policy acceptance |
| Synthetic benchmark and separate tuning/held-out sets | Automatically verified: 24 actual detector inputs, IoU/corner/timing/heap records | Awaiting physical image-quality acceptance |
| Reuse/license investigation | Implemented: candidate source manifests/licenses inspected | Automatically verified technical provenance/notices; exact CameraX libyuv revision unavailable |
| Open-source-only native OpenCV build, 64-bit ABIs and 16 KB alignment | Automatically verified: source-built minimal runtime, both ABI binary audits and x86_64 16KB execution | Awaiting arm64 device acceptance |
| EXIF-normalized import, convex crop, perspective, rotation, bounded rendering | Automatically verified: processing instrumentation | Awaiting real-camera coordinate/fidelity acceptance |
| Original/color/grayscale/BW/photo presets and conservative controls | Automatically verified: generated color/tonal fixtures | Awaiting faint text/photo fidelity acceptance |
| CameraX lifecycle, still capture, focus, torch, shared viewport/latest-frame analysis | Implemented; camera gate/mapping/storage unit tests and emulator manual shutter | Awaiting physical camera/thermal/focus/torch acceptance |
| Live boundary guidance, confidence/stability/exposure auto shutter and duplicate lockout | Automatically verified: detector benchmark and auto-gate sequence tests | Awaiting real-camera duplicate/missed-capture measurement |
| Improved Standard edge/corner detection and display-only tracking | Automatically verified RC3: separate challenge recall 1/6 to 5/6, no false positives; original held-out 10/10; 35 camera tests | Awaiting real-camera/thermal/user acceptance; one narrow receipt remains missed |
| Swipeable review/save, confirmed retake/delete/whole-session close, fullscreen camera and active scanning ring | Automatically verified RC4: emulator workflows including replacement order, page browsing and normal/200% text | Awaiting physical/user/accessibility acceptance |
| Reference-style save screen, filename validation and actual named PDF/JPEG outputs | Automatically verified RC3: both emulators, normal/200% font; PNG removed from UI | Awaiting user and host destination/account integration acceptance |
| Compose capture/import/crop/magnifier/corner sliders/appearance/page review | Automatically verified: ready-made UI emulator tests, 200% font/dark screenshot review | Awaiting TalkBack/switch/user UX acceptance |
| JPEG/PNG/image-PDF, dimensions/MIME/warnings/progress/cancellation/staged output commit | Automatically verified: actual encoder/PDF renderer and rollback tests | Awaiting independent-viewer and large-session device acceptance |
| Interrupted-session discard, no input deletion, retained successful outputs | Implemented; ownership/cancel tests, discard policy documented | Awaiting physical process-death/host cleanup acceptance |
| Generic OpenCloud/Kura integration adapters | Implemented: INTEGRATION.md and sample route | Awaiting host-app build/integration acceptance; no host repo changes authorized |
| Dependency graph/merged manifest/native audits | Automatically verified: final source-built APK, manifest/graph/native/provenance/notices | Awaiting physical/host acceptance |
| Local Maven artifacts, APK, source archive, licenses/checksums/evidence | Automatically verified: five local Maven artifacts, independent consumer, APK/source/checksums | No public publication authorized |
| OCR/searchable PDF, ML downloads/training, cloud, curved-page dewarp, glare stacking, generative restoration, cross-platform UI | Deferred beyond first release | Outside scope |
| Learned single-document detector, Standard/AI settings and shared corner/capture/crop flow | RC8 experimental DocQuadNet with pinned Apache inference grant, offline ORT CPU runtime, Standard/AI settings, shared corners and quality; emulator comparison is mixed; DocAligner weights remain blocked | Keep Standard default; require identical-footage quality, stability, capture, latency and sustained-performance comparisons before default changes |
| Simultaneous multi-document capture: detect/separate several documents in one frame, order pages and prevent duplicate re-scans | Deferred beyond first release, explicitly requested 2026-10-01 | Evaluate classical vision versus openly licensed bundled ML; offline from first launch, no Play services/private models. Benchmark split/merge errors, clipping, order, duplicates and peak memory before implementation claims |

Remaining checks are specified in DEVICE-ACCEPTANCE.md and QUALITY-REPORT.md. No unrelated repositories, production accounts, private images, purchases or public publishing were used.

