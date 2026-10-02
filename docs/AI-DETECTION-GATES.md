# Learned detection implementation gate

This work starts after the UI-only RC2 testing APK was built and uploaded. RC2 is immutable; learned detection belongs to a subsequent candidate.

## Required shared pipeline

Both detectors must return core Detection with a normalized upright Quad in TL/TR/BR/BL order, confidence, brightness, sharpness and scene signature. No runtime tensors or OpenCV classes may escape the processing boundary. The corner detector must be injectable for both camera bitmap analysis and still/import detection; RC8 provides both injection seams. EXIF normalization, scene quality measurement and signature extraction must remain common, so changing modes does not accidentally change duplicate recognition or shutter quality gates.

A shared tracker validates convex corners, follows the same document and smooths measurement noise. It resets on orientation/viewport changes, detector mode switches or genuine document replacement. Automatic capture must use current raw geometry and quality/stability observations rather than letting smoothing manufacture a stable scene; a stale outline must not trigger capture. Both modes feed the existing outline mapping, AutoCaptureGate, crop, review and page-management flow. Corner-edit geometry and original pixels remain unchanged.

Detection mode belongs in settings with Standard and AI options. Standard is default. AI is enabled only when a verified licensed model is bundled and its offline Android execution passes; an unavailable model must never silently masquerade as Standard. A mode switch must pause capture, invalidate in-flight analysis and reset tracking before new observations are delivered. The standalone sample and host session must use the same selection for live and still detection. No model download, Play services or network permission is allowed.

## Adoption and comparison gates

First candidate: the smallest officially documented DocAligner checkpoint. Pin exact code revision, model download provenance, checksum, input preprocessing/output decoding, code license and separately applicable weight license. Dataset rights and restrictions require independent review. Do not infer weight redistribution rights from a third-party mirror. Avoid optional runtime downloads.

Compare one pinned offline Android runtime against OpenCV DNN if DNN is a credible alternative; the current minimal OpenCV binary has no dnn module, so adding it requires a new source/provenance/license/native-alignment audit. Measure arm64 and x86_64 compatibility, 16KB pages, packaged size, cold start, latency, memory, sustained execution and model initialization failure behavior. An emulator cannot establish physical thermal performance.

Replay identical held-out footage through both modes: light backgrounds, clutter, shadows, narrow receipts, angled pages and slight camera movement. Report corner/IoU accuracy, misses/false positives, frame-to-frame outline movement, duplicate/missed captures, final crop clipping, p50/p95 latency, memory and sustained throughput. Keep source frames and ground truth distinct from detector output. Synthetic scenes support reproducible regression; real-camera footage/device measurements are necessary for an adoption/default-switch decision.

Simultaneous multi-document detection is a separate future feature. No new training is authorized.
## RC8 status

DocAligner weights remain unresolved. A separately licensed DocQuadNet export now runs offline on API35 and a verified 16KB API37.1 x86_64 emulator. Settings, shared metrics/tracking/capture/crop and session-preserving switches are implemented and tested. Standard remains default. Mixed synthetic comparison results and pending real handheld/arm64 acceptance prevent default promotion. See LEARNED-DETECTOR-RC8.md for exact license scope, runtime choices and quality evidence.

RC9 update: following user physical-phone feedback that AI detects documents substantially better, the standalone app and the ready-made ScannerFlow default now select AI; explicitly constructed ScanConfig retains its Standard default for existing custom hosts. Standard remains selectable in settings. The bundled weights, decoder and capture-quality thresholds are unchanged; synthetic limitations remain recorded in RC8 evidence.
