# Scanner design

## Purpose and audience

The scanner makes local copies of documents, receipts, cards and printed photos. Android app developers can embed the ready-made interface or use the processing modules directly. The sample app shows the supported workflow. It is not a document library.

## Screens and journeys

| Screen | Primary task | Recovery and other actions |
| --- | --- | --- |
| Capture | Frame a page, then use the shutter or automatic capture. | Import a file if the camera is unavailable. Use manual capture if detection does not find a page. Status text gives capture feedback. |
| Crop and appearance | Adjust the corners. Keep Auto or select an appearance filter. | Start from the original. Use the magnifier and precise corner controls to adjust an edge. Changes do not overwrite the original. |
| Review | Check each page in order before export. | Swipe between pages. Zoom in to inspect detail. Use retake, reorder or delete when necessary. Confirm an action that discards a page. |
| Save | Name the output and select PDF or image output. | Show progress during export. Cancel or retry a failed operation without changing the original. The host controls the output directory and what happens to the completed files. |
| Sample landing | Start another scan or open the latest completed output. | Show a result or a failure. The sample does not show the full scan history. |

The [scanner flow](../scanner-ui-compose/src/main/kotlin/dev/offlinescan/ui/ScannerFlow.kt) connects capture, crop, review and save. See [Review](../scanner-ui-compose/src/main/kotlin/dev/offlinescan/ui/ScannerReview.kt), [Save](../scanner-ui-compose/src/main/kotlin/dev/offlinescan/ui/ScannerSave.kt) and the [last-scan panel](../scanner-sample/src/main/kotlin/dev/offlinescan/sample/SavedScanPanel.kt) for their controls.

## Visual identity

**Proposed intent:** Keep the scanner quiet and practical. Make the page the focus. The interface is a camera and an editor, not a gallery, a cloud drive or a document filing app. This summary describes the current interface; it does not set a new brand.

**Use:** Keep the large shutter distinct from secondary actions. Use color with labels and status text. Use Material 3 controls for editor actions and host screens. Follow familiar Android camera and system picker conventions for capture and import.

**Avoid:** Do not add decoration over the page, make color the only status cue, or use the sample launcher icon as the host app’s icon.

| Decision or asset | Source | Use |
| --- | --- | --- |
| Sample theme | [MainActivity](../scanner-sample/src/main/kotlin/dev/offlinescan/sample/MainActivity.kt) | The sample uses Material 3 type and controls with its own light and dark color schemes. The host supplies the Material theme for the embedded editor. |
| Camera colors and controls | [ScannerFlow](../scanner-ui-compose/src/main/kotlin/dev/offlinescan/ui/ScannerFlow.kt) and [ScannerChrome](../scanner-ui-compose/src/main/kotlin/dev/offlinescan/ui/ScannerChrome.kt) | The capture screen uses dark chrome and a distinct outline. Canvas glyphs are editable in code. Icon buttons have names and 48 dp hit areas. |
| Sample launcher icon | [Vector drawable](../scanner-sample/src/main/res/drawable/scanner_icon.xml) and [manifest](../scanner-sample/src/main/AndroidManifest.xml) | The checked-in page icon is the editable source and the packaged launcher asset. Android renders it from the vector; there is no separate export. The repository documents no separate provenance record, other icon variant or storefront artwork. |

### Real captures

These links show earlier Android emulator builds with synthetic content. They do not verify the current release or a physical device.

| Capture | Source and steps |
| --- | --- |
| [RC4 camera](../evidence/rc4-camera-fullscreen-16k.png) | RC4, 16 KB page-size emulator. Open the sample and enter Capture with the emulator camera. Dark capture chrome is independent of the host theme. See [RC4 capture notes](UI-CAPTURE-RC4.md). |
| [RC4 Save, dark, large text](../evidence/rc4-save-large-font-dark.png) | RC4 emulator evidence. Set the system to dark theme and 200% text, scan or import a synthetic page, then proceed from Review to Save. See the [test record](UI-CAPTURE-RC4.md). |
| [RC10 review with large text](../evidence/rc10-review-large.png) | RC10 emulator evidence. Import synthetic pages, advance to Review and inspect the page controls at large text. See the [RC10 recorded checks](ENHANCEMENT-RC10.md#recorded-checks). |

If the interface changes, update its design guidance and the affected captures in the same change. Record the build, test device or viewport, theme, text scale and state. Keep the original vector as the source for the sample launcher icon.

## Accessibility and privacy

Keep the shutter easy to reach. Keep less-used actions out of the camera preview. Use text to explain failures and let the user adjust a crop when detection does not work. Check the current release in both themes, at 200% text size, and with TalkBack and alternate input. These checks remain open unless the [device acceptance record](DEVICE-ACCEPTANCE.md) supplies evidence for that build.

The scanner requests camera access for capture. It uses the system picker for import. No upload or account is required. Optional OCR uses local data. See [Architecture](ARCHITECTURE.md) for ownership, cancellation and storage boundaries.

## Verification

Use the [sample flow tests](../scanner-sample/src/androidTest/kotlin/dev/offlinescan/sample/ScannerFlowTest.kt) for import, crop, review, PDF export, cancellation and malformed input. Use the [saved scan tests](../scanner-sample/src/androidTest/kotlin/dev/offlinescan/sample/SavedScanPanelTest.kt) for the sample result screen. The tests and historical captures do not replace the [physical-device checks](DEVICE-ACCEPTANCE.md). Current visual acceptance and owner review remain open.
