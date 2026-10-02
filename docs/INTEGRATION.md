# Generic host integration (OpenCloud / Kura)

No host repositories or accounts were modified. Examples express the boundary only; host APIs and acceptance must be checked in their actual repositories.

## Source integration

Include the scanner modules in the host build (or consume the generated local Maven repository described in RELEASE.md). Depend on `scanner-ui-compose` for the complete flow. For custom UI depend on core, processing and export only; none requires host accounts, wallet models or networking.

The UI library merges CAMERA permission and camera hardware is optional. Import uses Android's document provider; no broad media/storage permission is requested. A processing-only consumer has no CAMERA permission. OpenCloud/Kura should choose their supported min SDK, application theme, native ABIs and FileProvider. API 26/64-bit support is the SDK default; incompatible host requirements need an explicit compatibility decision.

## OpenCloud-shaped adapter

```kotlin
// In a scanner-only host route. Upload is host work after completion.
ScannerFlow(config = ScanConfig(mode = ScanMode.DOCUMENT),
    outputDirectory = File(context.filesDir, "pending-scans")) { result ->
    when (result) {
        is ScanResult.Completed -> {
            // Present host naming/destination UI; then enqueue host upload.
            onLocalScanReady(result.output.files, result.output.mimeType)
        }
        ScanResult.Cancelled -> dismissScanner()
        is ScanResult.Failed -> presentHostError(result.error)
    }
}
```

Never delete the returned files until the host has persisted/uploaded them successfully or the user explicitly discards them. Upload cancellation and scan cancellation are independent.

## Kura-shaped adapter

```kotlin
ScannerFlow(config = ScanConfig(mode = ScanMode.CARD, cardFrontBack = true),
    outputDirectory = File(context.filesDir, "pending-card-images")) { result ->
    when (result) {
        is ScanResult.Completed -> onCardImagesReady(result.output)
        ScanResult.Cancelled -> dismissCapture()
        is ScanResult.Failed -> presentHostError(result.error)
    }
}
```

Host explicitly chooses front/back order, categories and attachment rules. The SDK does no verification, OCR, extraction or automatic wallet insertion. Card images can contain sensitive data; host storage/backup policy remains host responsibility.

## Acceptance recipe

1. Resolve the local Maven artifacts and run each host's actual build, lint and tests; inspect resolved versions/merged manifest for incompatibility or added permissions.
2. Launch while signed out and while offline without enabled Google Play services. Import from a document provider; deny camera permission and confirm usable import/manual recovery.
3. Complete/cancel/fail a session and verify the host route handles all outcomes once, naming/sharing/storage work through host mechanisms and outputs survive navigation.
4. Exercise large font, TalkBack, light/dark, configuration changes/background/process death. Active sessions intentionally discard; host does not interpret cancellation as failure.
5. Capture front/back cards and multi-page documents, reorder/replace/remove, confirm host retains the final page order and dimensions.

These examples have not been integrated or accepted in OpenCloud or Kura. No host-specific API names are represented as verified calls.
