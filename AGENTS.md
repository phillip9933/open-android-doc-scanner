# Working on offline-scanner

## Load the central guidance before work

This project uses Phil's central guidance for local AI agents. Common rules stay in the master; project-specific commands and rules stay below.

- Resolve an owner-configured absolute `APP_GUIDELINES_ROOT` if it is supplied by the trusted local launch environment. Otherwise the confirmed laptop master is `/home/phil/Projects/App_Guidelines`. Do not assume that laptop path exists on a future NAS/other executor; obtain its explicit accessible path first.
- Explicitly read `<resolved root>/agent-guidelines.md` and its task-relevant guides before repository changes or execution. Do not assume Markdown, a variable name or an import loads those files automatically.
- If the configured root, entrypoint or required guide is missing, inaccessible or denied, report the exact blocker. Do not silently fall back, skip it, use cached/project copies or bypass access controls. Stop repository-changing/executing work until access is resolved or the owner explicitly decides how to handle the task; only authorized read-only context gathering may continue.
- Follow the current central revision, keep existing project-specific rules and report conflicts. Record the central version actually read in private task evidence, rather than pinning stale guide copies here.
- Never copy private chat/planning, internal AI reasoning or messages addressed to Phil into commits, PRs or other public artifacts.

## Project workspace layout

Before working, read the project and shared workspace guidance when accessible. In `<project>/repo/`, those files are `../AGENTS.md` and `../../AGENTS.md`. In `<project>/worktrees/<name>/`, those files are `../../AGENTS.md` and `../../../AGENTS.md`. If either is unavailable in your environment, report that limitation and follow the local rules below. Preserve this repository's existing instructions and any applicable nested instructions; historical release permissions are not current task authorization.

Run source commands from the active checkout. The authoritative source checkout is `<project>/repo/`; use a linked worktree only for its assigned change. Keep versioned documentation in `docs/` when consistent with existing conventions. Put task deliverables in `../outputs/`; consult `../reference/` for supporting material and `../archive/` for recovery material. Use `../worktrees/` only for real linked worktrees. Create these directories only when needed, and retain conventional build-output paths required by tools. Global caches and app configuration remain global.

Keep signing material outside Git in `../private/signing/`. Do not read, print, commit, publish or activate credentials; signing configuration or use requires explicit user authorization. Do not initialize Git in the project container (`..`) or Projects directory (`../..`). Inspect status before edits, preserve user changes and ignored source, run relevant checks, and report limitations. Keep external backups unchanged. Instructions guide behavior; they do not technically guarantee compliance.


## This project

- **Purpose and tier:** Public Android scanner SDK and sample app. The SDK is for Android app developers; the sample demonstrates the scanner flow. Apply the native/mobile and library/distribution profiles.
- **Platform and capabilities:** Android API 26 or later on arm64-v8a and x86_64. The SDK handles camera images, imported files and optional offline OCR. It has no network permission. Use synthetic development fixtures.
- **Distribution:** The [GitHub release](https://github.com/phillip9933/open-android-doc-scanner/releases) supplies the SDK Maven archive and its checksum. The sample is a development app, not a store release.
- **Setup and run:** Use the toolchain in the [Build section](README.md#build). Prepare the pinned OCR test models with `python3 tools/prepare_ocr_test_models.py`. Use `./gradlew :scanner-sample:installDebug` on an approved test device or emulator, then open **Offline Scanner**.
- **Tests and checks:** See the [contribution instructions](CONTRIBUTING.md) and [build workflow](.github/workflows/build.yml) for the JVM, lint, package and consumer checks. Use the [device acceptance checklist](docs/DEVICE-ACCEPTANCE.md) for connected tests. No measured suite timing or branch-protection policy was verified here.
- **Design and architecture:** See [Design](docs/design.md) and [Architecture](docs/ARCHITECTURE.md). The sample theme and scanner camera controls have separate visual rules.
