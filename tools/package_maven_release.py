"""Create a reproducible SDK-only Maven archive and SHA256SUMS for a candidate."""

from __future__ import annotations

import hashlib
import re
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1]
build_file = (root / "build.gradle.kts").read_text(encoding="utf-8")
match = re.search(r'allprojects\s*\{[^}]*version\s*=\s*"([^"]+)"', build_file, re.S)
if not match:
    raise SystemExit("Could not find the shared SDK version in build.gradle.kts")
version = match.group(1)

maven_root = root / "release" / "maven"
modules = ("scanner-camera", "scanner-core", "scanner-export", "scanner-processing-opencv", "scanner-ui-compose", "scanner-ocr-tesseract")
def publication_files(artifact_root: Path, module: str, version: str) -> list[Path]:
    """Resolve only this publication, including Maven's timestamped snapshot names."""
    suffix = "jar" if module == "scanner-core" else "aar"
    metadata = artifact_root / "maven-metadata.xml"
    if version.endswith("-SNAPSHOT"):
        tree = ET.parse(metadata)
        selected = {}
        for item in tree.findall("./versioning/snapshotVersions/snapshotVersion"):
            extension = item.findtext("extension", "")
            classifier = item.findtext("classifier", "")
            value = item.findtext("value", "")
            if not re.fullmatch(re.escape(version[:-9]) + r"-\d{8}\.\d{6}-\d+", value):
                raise ValueError("Invalid snapshot version in Maven metadata")
            if not re.fullmatch(r"[A-Za-z0-9_-]*", classifier) or not re.fullmatch(r"[A-Za-z0-9]+", extension):
                raise ValueError("Invalid snapshot artifact name")
            selected[(extension, classifier)] = artifact_root / f"{module}-{value}{('-' + classifier) if classifier else ''}.{extension}"
        for extension in (suffix, "pom", "module"):
            if (extension, "") not in selected:
                raise ValueError(f"Missing snapshot {extension}: {module}")
        files = [metadata, *selected.values()]
    else:
        files = [artifact_root / f"{module}-{version}.{extension}" for extension in (suffix, "pom", "module")]
        files += list(artifact_root.glob(f"{module}-{version}-*.jar"))
    result = []
    for file in files:
        if not file.is_file():
            raise FileNotFoundError(f"Missing publication input: {file}")
        result.append(file)
        result.extend(checksum for ext in ("md5", "sha1", "sha256", "sha512") if (checksum := file.with_name(file.name + "." + ext)).is_file())
    return result

publication_inputs = []
for module in modules:
    publication_inputs.extend(publication_files(maven_root / "dev" / "offlinescan" / module / version, module, version))

required_root_files = ("LICENSE", "NOTICE", "THIRD-PARTY-NOTICES.md")
for name in required_root_files:
    if not (root / name).is_file():
        raise SystemExit(f"Missing required license material: {root / name}")
if not (root / "third-party").is_dir():
    raise SystemExit("Missing third-party notices directory")
if not (root / "docs" / "RELEASE-0.8.0.md").is_file():
    raise SystemExit("Missing docs/RELEASE-0.8.0.md")

release = root / "release"
release.mkdir(exist_ok=True)
archive_path = release / f"open-android-doc-scanner-{version}-sdk-maven.zip"
archive_inputs = [
    (path, Path("maven") / path.relative_to(maven_root))
    for path in sorted(publication_inputs)
]
archive_inputs.extend((root / name, Path(name)) for name in required_root_files)
archive_inputs.extend(
    (path, Path("third-party") / path.relative_to(root / "third-party"))
    for path in sorted((root / "third-party").rglob("*"))
    if path.is_file()
)
archive_inputs.append((root / "docs" / "RELEASE-0.8.0.md", Path("RELEASE-NOTES.md")))
archive_inputs.append((root / "docs" / "NATIVE-BUILD.md", Path("NATIVE-BUILD.md")))
for name in ("OCR.md", "OCR-SECURITY.md"):
    archive_inputs.append((root / "docs" / name, Path("docs") / name))
archive_inputs.append((root / "evidence/ocr-native-build.json", Path("evidence/ocr-native-build.json")))

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
