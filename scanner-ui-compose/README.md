# Compose flow

`dev.offlinescan.ui.ScannerFlow(config, outputDirectory, onResult)` supplies one capture/import/edit/page-review session. Place it in MaterialTheme; values should stay stable for that launch. Handle Completed/Cancelled/Failed by leaving the route. Successful outputs stay host-owned; source originals are copied into private cache and discarded on disposal after in-flight processing exits.

Use API26+, Camera permission for capture only, and the system picker for imports. The full review list and controls scroll together; crop has dragging, magnified detail, labeled sliders and small directional corner steps. Strings are overridable/localizable. Printed-photo mode defaults to PHOTO. UI recomposes bounded previews from original pixels and edit parameters.

Displayed bitmaps are Android-GC-owned because Compose/RenderThread can retain draw lists after state changes. Undelivered worker results recycle explicitly; source/native render allocations also have explicit cleanup. Preview/source work is tracked through a session lifetime lease. Export cancellation uses a token and preserves cancellation, and undelivered committed outputs are removed without deleting previous successful scans.

The sample instrumentation uses only synthetic files and deterministic picker results, plus an emulator camera scene. It exercises 200% fonts/dark theme, manual import/crop/appearance, two-page reorder/PDF, invalid image failure, cancellation and manual camera capture. These are not TalkBack, physical-camera, or host-app acceptance claims.
