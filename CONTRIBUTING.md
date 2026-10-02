# Contributing

Open an issue for bugs or proposed features, including Android version, device model, detection/capture mode, and reproduction steps. Do not attach private documents; use a synthetic or redacted example you have permission to share.

Use JDK 17/21, Android SDK 36 and NDK 28.2.13676358. Run the build, JVM tests and lint described in README.md. Processing/export/UI instrumentation tests need a supported device or emulator. Keep originals immutable and processing bounded, cancellable and offline. Include meaningful regression checks for image-processing or gesture changes.

Preserve Apache-2.0 and all applicable third-party/model notices. Explain dependency/model changes and their license/provenance. Use pull requests; describe the behavior change, validation and remaining limitations.
