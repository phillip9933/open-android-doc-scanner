# Positioning labels, focus and page peeks - RC7

The capture-mode button uses exactly Position a Document, Position a Photo, Position a Receipt, and Position a Card. Existing live guidance remains available through the button's accessibility state description. Mode selection and automatic/manual behavior are unchanged.

Tap-to-focus is best effort. CameraX cancels a pending focus request when another starts; the old listener incorrectly reported this through the fatal camera-error callback. Unsupported metering is now skipped and failed/cancelled focus requests do not close the camera or show a capture error. Image capture and binding failures retain their existing error handling. This does not guarantee successful autofocus or capture on every physical camera.

Review and save show 16 dp clipped slivers of the actual adjacent processed pages, where those pages exist. The selected page remains centered and fully fitted. There are no navigation arrows. Both screens retain their swipe gestures, page ordering and preview cache. Neighbor renderers are keyed by page identity and use the same bounded session cache; unloaded neighbors have a neutral paper placeholder.

Validation: 52 camera unit tests pass; four complete API 35 Android UI tests pass at normal text size. Tests verify all four exact labels, double tapping the preview without a fatal dialog followed by consecutive captures, correct directional peeks after swiping, absence of arrows, non-destructive Back, confirmed retake/delete, and exported page order. The complete import/edit/reorder/retake/delete/swipe/save/PDF flow also passes at 200% text size. Normal and large-text screenshots were inspected. Lint, local publication, independent consumer build and exact APK native/permission/license audit pass.

The first normal test run overlapped automatic capture with the focus assertion. The final harness pauses automatic capture while testing focus and resumes afterward; the corrected complete run passed. See evidence/ui-rc7-api35-normal.txt and ui-rc7-api35-large.txt. Standard detection and native binaries are unchanged. Physical handset focus/capture confirmation remains pending.
