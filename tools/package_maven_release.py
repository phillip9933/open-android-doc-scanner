"""Create a reproducible SDK-only Maven archive and SHA256SUMS for a candidate."""

from __future__ import annotations

import hashlib
import re
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
build_file = (root / "build.gradle.kts").read_text(encoding="utf-8")
match = re.search(r'allprojects\s*\{[^}]*version\s*=\s*"([^"]+)"', build_file, re.S)
if not match:
    raise SystemExit("Could not find the shared SDK version in build.gradle.kts")
version = match.group(1)

maven_root = root / "release" / "maven"
modules = ("scanner-camera", "scanner-core", "scanner-export", "scanner-processing-opencv", "scanner-ui-compose")
for module in modules:
    artifact_root = maven_root / "dev" / "offlinescan" / module / version
    suffix = "jar" if module == "scanner-core" else "aar"
    artifact = artifact_root / f"{module}-{version}.{suffix}"
    pom = artifact_root / f"{module}-{version}.pom"
    metadata = artifact_root / f"{module}-{version}.module"
    for required in (artifact, pom, metadata):
        if not required.is_file():
            raise SystemExit(f"Missing RC artifact input: {required}")

required_root_files = ("LICENSE", "NOTICE", "THIRD-PARTY-NOTICES.md")
for name in required_root_files:
    if not (root / name).is_file():
        raise SystemExit(f"Missing required license material: {root / name}")
if not (root / "third-party").is_dir():
    raise SystemExit("Missing third-party notices directory")
if not (root / "docs" / "RELEASE-RC11.md").is_file():
    raise SystemExit("Missing docs/RELEASE-RC11.md")

release = root / "release"
release.mkdir(exist_ok=True)
archive_path = release / f"open-android-doc-scanner-{version}-sdk-maven.zip"
archive_inputs = [
    (path, Path("maven") / path.relative_to(maven_root))
    for path in sorted(maven_root.rglob("*"))
    if path.is_file()
]
archive_inputs.extend((root / name, Path(name)) for name in required_root_files)
archive_inputs.extend(
    (path, Path("third-party") / path.relative_to(root / "third-party"))
    for path in sorted((root / "third-party").rglob("*"))
    if path.is_file()
)
archive_inputs.append((root / "docs" / "RELEASE-RC11.md", Path("RELEASE-NOTES.md")))
archive_inputs.append((root / "docs" / "NATIVE-BUILD.md", Path("NATIVE-BUILD.md")))

temporary_archive = archive_path.with_suffix(archive_path.suffix + ".tmp")
with zipfile.ZipFile(temporary_archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for source, archive_name in sorted(archive_inputs, key=lambda item: item[1].as_posix()):
        info = zipfile.ZipInfo(archive_name.as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.create_system = 3
        info.external_attr = 0o100644 << 16
        archive.writestr(info, source.read_bytes())

with zipfile.ZipFile(temporary_archive) as archive:
    bad_member = archive.testzip()
    if bad_member:
        temporary_archive.unlink()
        raise SystemExit(f"Generated Maven archive failed integrity check: {bad_member}")

temporary_archive.replace(archive_path)
digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
(release / "SHA256SUMS").write_text(
    f"{digest}  {archive_path.name}\n",
    encoding="ascii",
)
print(f"Created {archive_path}")
print(f"SHA256 {digest}")
