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

## Work tracking and release policy

The [public project](https://github.com/users/phillip9933/projects/1) is the live roadmap and work board. Issues are the source of truth for scope and verification; repository documents retain design decisions and historical evidence, not a duplicate live task list.

### Labels and triage

Every issue has exactly one purpose label:

| Label | Purpose |
| --- | --- |
| `type:bug` | Existing behavior is broken |
| `type:feature` | New or changed user-facing capability |
| `type:maintenance` | Refactoring, dependencies, CI, performance or technical debt |
| `type:docs` | Documentation-only work |
| `type:release` | Release scope and promotion gates |

Choose the purpose, not every activity: a bug fix requiring refactoring remains a bug. After triage, assign one priority: `priority:urgent` (immediate action), `priority:high` (next significant work), `priority:normal` (default accepted work), or `priority:low` (worthwhile but not currently important). Priority determines work order, not a delivery promise. Keep suspected vulnerabilities out of public issues; follow [SECURITY.md](SECURITY.md).

### Work status

Use the Project Status field: **Inbox → Ready → In progress → Done**, with **Blocked** when a recorded dependency prevents progress. Inbox needs assessment; Ready is accepted and sufficiently defined; In progress is actively underway. Blocked items explain what will unblock them. Done requires acceptance criteria and verification evidence; it does not mean shipped.

Close rejected or duplicate issues with the appropriate reason, not as completed work. Do not use status labels. Review automatic Project workflows so rejected/duplicate closures do not become Done and completed work is not hidden before release.

### Release targets and maturity

A milestone names the intended stable version, such as **Android SDK 0.8.0**. Assign it only when the work is intended for that release. Unscheduled ideas have no milestone; alpha, beta and RC builds share the stable target. This Android-only SDK does not need redundant platform labels. Multi-platform repositories may use `platform:android`, `platform:desktop` and `platform:shared`. Cross-repository release issues link each repository's milestone.

Each release has one owner and a separate Project **Stage** field:

| Transition | Minimum evidence |
| --- | --- |
| Planned → Alpha | Scope, acceptance criteria and supported platforms recorded; an installable build exists |
| Alpha → Beta | Intended features implemented; core workflows pass; remaining limitations documented |
| Beta → RC | Release blockers resolved; required regression, upgrade/migration and data-safety tests pass; scope frozen |
| RC → Stable | Final candidate validated; notes and limitations ready; signed/checksummed artifacts verified where applicable; release published |

Dates do not pass gates. Stable means actually published. A feature entering after RC moves the release back to Beta and requires affected checks again. For the scanner, data safety includes preserving originals, ownership of outputs, cancellation/failure cleanup and interruption behavior. For Kura/Raiun, add relevant encryption, storage, backup/restore and sync checks. Host-specific card interpretation belongs in the host repository.

### Issue contents and public views

Every issue states its goal/problem, scope and exclusions, a **Done when** checklist, dependencies, and verification results or links before closing. Release issues also state target version, owner, included issues, gate checklists, remaining blockers and candidate/release links.

**Roadmap** shows only `type:release` issues on a timeline with Stage, Estimated start and Estimated target. Dates remain blank until a defensible estimate exists. **Work board** shows bugs, features, maintenance and docs grouped by Status. GitHub displays this information; gate checklists are maintainer policy, not automatic enforcement.
