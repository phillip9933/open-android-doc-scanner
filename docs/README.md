# Documentation index

Start with [README](../README.md) for build and API examples, [Contributing](../CONTRIBUTING.md) for change-specific checks and [Security](../SECURITY.md) for vulnerability reporting.

The [full documentation index](INDEX.md) remains the canonical map of integration, architecture, release, quality and historical evidence. Module-specific guides live beside [camera](../scanner-camera/README.md), [processing](../scanner-processing-opencv/README.md) and [Compose flow](../scanner-ui-compose/README.md).

## Repository layout

- `scanner-core/`: Kotlin/JVM contracts, geometry and session ownership.
- `scanner-camera/`, `scanner-processing-opencv/`, `scanner-export/`, `scanner-ui-compose/`: Android capture, processing, export and UI implementations.
- `scanner-sample/`: standalone host and UI/device tests.
- `tools/`: native builds, evidence collection, audits and local packaging.
- `docs/`, `evidence/`, `benchmarks/`: guidance, dated measurements and quality thresholds.
- `third-party/`: retained license and provenance records. Runtime notices also live in processing assets.

Modules are declared in [settings.gradle.kts](../settings.gradle.kts). Build outputs and locally packaged releases are ignored; tracked evidence is historical, not a fresh result for every revision.
