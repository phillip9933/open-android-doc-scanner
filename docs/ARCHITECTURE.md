# Architecture and decisions

One launch creates one private in-memory session. No historical library, account, upload, OCR, model download, network permission, analytics or licensing endpoint exists.

## Contracts and boundaries

`scanner-core` is Kotlin/JVM with configuration, normalized upright geometry, immutable edit parameters, typed outcomes, processing contracts and owned session files. It has no Android, CameraX, Compose or OpenCV dependency. `ImageProcessor` accepts `File` and returns project value types. Original session images are preserved and every render starts from the original.

`scanner-processing-opencv` implements the contracts using bounded decode, EXIF normalization, classical contour detection, perspective warp and conservative preset transforms. OpenCV types do not escape public interfaces. Detection can fail honestly; manual crop is always available. Printed-photo defaults to PHOTO, never thresholding or document illumination normalization.

`scanner-camera` uses CameraX with latest-frame backpressure and one analysis worker. `scanner-ui-compose` supplies the ready-made session flow. `scanner-export` renders individual JPEG/PNG or image-based PDF with staged commits and cancellation cleanup. `scanner-sample` demonstrates independent host consumption.

## Platform

Android API 26 minimum, compile/target 36, Java 17 bytecode. API 26 avoids legacy storage/platform lifecycle support; OpenCloud and Kura actual minimum/API compatibility still requires host acceptance. No changes made to either host repository. Native targets arm64-v8a for modern devices and x86_64 for emulators. 32-bit devices are excluded intentionally. AGP 8.13.2, Gradle 8.13, Kotlin 2.2.20 and CameraX 1.4.2 pinned. OpenCV 4.12.0 Java wrappers accompany source-built core/imgproc/JNI only, using NDK 28.2.13676358. Stock AAR native binaries are excluded because the x86 configuration includes IPP. Exact source/toolchain/flags/notices are retained. Both ABIs pass ELF/ZIP 16 KB alignment; the final x86_64 processing/export runtime was executed with PAGE_SIZE=16384. Physical arm64 acceptance remains pending.

## Ownership and interruption

Final sample compile/target SDK is36, consistent with the current Android16 distribution baseline. Primary target-policy reference: https://developer.android.com/google/play/requirements/target-sdk . This adds no Play services dependency; target API and runtime service dependencies are separate. Initial development used35 and was raised during release review. Dependencies are pinned to tested versions rather than automatically upgraded on every build.

`ScanSession` creates a UUID subdirectory under a caller-provided private cache root. Import copies encoded bytes under a 64 MiB limit; inspect validates input geometry/pixels before decoding. Closing removes only that session directory. Host original files and committed exports remain untouched. Session APIs are single-thread confined; UI serializes jobs and closes after cancellation/join. There is no recovery journal: activity/process interruption discards the active session. Hosts must discard stale private session directories only while no session is active. Export creates a `.pending-UUID` subdirectory, checks cancellation between pages and major stages, and renames to an exclusive committed directory only on success. Completed outputs belong to the host and need explicit host retention/sharing/cleanup.

## Coordinates and resources

Corners are normalized relative to the visually upright original image, TL/TR/BR/BL in clockwise order. EXIF correction precedes detection and rendering. Editing rotation is applied after crop and is independent of corner coordinates. No fixed paper aspect ratio is imposed. Convexity, nonintersection, finite bounds, minimum area and output limits are enforced before warp. Live detection uses low resolution; export uses bounded higher resolution. Camera shared viewport crops analysis and still capture consistently, then rotates analysis upright. Detection suggestions must still be reviewed on the captured image because shutter timing can move the document.

Native Mat objects, bitmaps, camera frames, PDF documents and streams have explicit lifetimes. Cancellation is checked cooperatively; single codec/native calls cannot be interrupted midway. Memory and latency gates must be evaluated on target devices. Image-based PDF uses Android PdfDocument; it can retain multiple page images, so large sessions need peak-memory acceptance before release.

## Release boundaries

Deferred: OCR/searchable PDF, simultaneous multi-document detection, book dewarping, multi-frame glare removal, generative reconstruction, cloud, cross-platform UI and model training. Card mode only produces images; it performs no identity verification or wallet-field extraction.


## RC8 shared detection and capture

This section records RC8 behavior. In the current source, `ScanConfig` still defaults to Standard, while `ScannerFlow` and the sample default to AI. See the RC9 entry in [quality history](QUALITY-REPORT.md); this default change does not establish better detection on every input.

Experimental learned single-document detection uses bundled DocQuadNet-256 and ONNX Runtime CPU inference. Both detectors return the same Detection/Quad values and document-interior quality metrics; the UI injects the selected detector for live, still and imported images. Settings switches close the old camera generation, pause capture and reset outline tracking while preserving session pages. Standard stays default; AI does not silently fall back to Standard. Standalone settings apply within the current session; hosts may supply DetectionMode in ScanConfig. Processing-only users explicitly call LearnedDocumentDetector on a worker and close it after jobs finish; OpenCvProcessor remains the Standard implementation.

Automatic capture focuses with a bounded wait, validates fresh geometry, assesses two original JPEGs and commits the sharper acceptable original without fusion/recompression. Capture cancellation and lifecycle interruption remove owned staging files. Manual capture remains available when automatic quality requirements cannot be met.

RC9 update: following user physical-phone feedback that AI detects documents substantially better, the standalone app and the ready-made ScannerFlow default now select AI; explicitly constructed ScanConfig retains its Standard default for existing custom hosts. Standard remains selectable in settings. The bundled weights, decoder and capture-quality thresholds are unchanged; synthetic limitations remain recorded in RC8 evidence.
