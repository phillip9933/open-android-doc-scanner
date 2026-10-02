# Contributing to Open Android Doc Scanner

Start with [README](README.md), [Architecture](docs/ARCHITECTURE.md) and [Security](SECURITY.md). Discuss major feature, dependency or model changes in an issue before implementing them.

## Development

Use JDK 17 or 21, Android SDK platform 36 and NDK 28.2.13676358. Set `ANDROID_HOME` or an ignored `local.properties` containing `sdk.dir`. Use the checked-in Gradle wrapper from the repository root. On Windows, substitute `.\gradlew.bat` for `./gradlew`.

```sh
./gradlew :scanner-core:test :scanner-camera:testDebugUnitTest :scanner-sample:assembleDebug lint
```

CI uses JDK 21 and build-tools 35.0.0. [Native build reproduction](docs/NATIVE-BUILD.md) covers rebuilding the bundled native libraries; it is separate from ordinary Kotlin/app builds.

## Choose tests by change

| Change | Validation |
| --- | --- |
| Session, geometry and shared contracts | `:scanner-core:test` |
| Camera policy and analysis | `:scanner-camera:testDebugUnitTest`, then relevant real-camera acceptance |
| Processing, crop, enhancement or export | Processing/export instrumentation and representative image-quality evidence |
| Compose flow and gestures | Sample instrumentation plus accessibility and physical-device checks |
| Native dependencies or packaging | [Release checks](docs/RELEASE.md) and [native provenance](docs/NATIVE-BUILD.md) |
| Documentation | Check commands, paths and links against tracked files; no app suite needed for prose alone |

Run instrumentation only on a disposable supported emulator or test device with synthetic fixtures:

```sh
./gradlew :scanner-processing-opencv:connectedDebugAndroidTest :scanner-export:connectedDebugAndroidTest :scanner-sample:connectedDebugAndroidTest
```

The build workflow assembles instrumentation APKs but does not run device tests. [Quality evidence](docs/QUALITY-REPORT.md) and [device acceptance](docs/DEVICE-ACCEPTANCE.md) distinguish measured results from pending work. Synthetic results do not establish physical-camera quality.

## Structure and code style

I prioritize data safety, security, correctness and privacy, followed by usability, maintainability, simplicity, compatibility, extensibility, performance and architectural purity. Prefer small, understandable changes with clear ownership. Explain exceptions to defaults and preserve existing data and public contracts. Consider accessibility and localization when changing UI. These are review priorities, not a claim that every current implementation meets them.

Keep originals immutable and processing bounded, cancellable and offline. Keep Android/OpenCV types out of `scanner-core`; hosts own persistent output storage, sharing and navigation. Follow the surrounding Kotlin style and avoid unrelated formatting. Include meaningful regression checks for image-processing or gesture changes. Hosts can override/localize UI strings; validate large fonts and accessibility for UI changes.

## AI usage

Disclose material AI assistance in the PR/commit summary: whether it was used for analysis, documentation, code or tests, and which checks you personally verified. You remain responsible for understanding the submitted change. Do not present generated test claims as observed results or share private documents with an assistant. Development assistance is separate from the SDK's optional learned document detector and its model provenance.

## Reports and pull requests

Include the SDK/sample version, Android version, device model, detection/capture mode and reproduction steps. Use a synthetic or redacted example you have permission to share. Report vulnerabilities through [Security](SECURITY.md), not a public issue containing private documents or exploit details.

PRs should describe the problem, behavior change, validation and remaining limitations. Update affected docs. Explain dependency/model changes and their license/provenance. Preserve [Apache-2.0](LICENSE), [NOTICE](NOTICE) and all applicable [third-party/model notices](THIRD-PARTY-NOTICES.md).

I'm happy to help where I can, but I can't promise a response or support schedule. Keep discussion respectful and technical.
