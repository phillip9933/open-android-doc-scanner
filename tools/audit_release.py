#!/usr/bin/env python3
"""Offline, standard-library release evidence. Exit 1 on missing/failed artifact checks.

Run after assembleDebug and writing the sample debugRuntimeClasspath dependency report:
    python tools/audit_release.py
    python tools/audit_release.py --self-test

This checks binary/packaging properties; it does not certify native licensing or device behavior.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import struct
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

ALIGNMENT = 16384
OPENCV_VERSION = "4.12.0"
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"
FORBIDDEN_GROUPS = ("com.google.android.gms", "com.google.firebase", "com.google.mlkit",
                    "com.google.android.odml", "com.google.android.play")
NOTICE_NAME = re.compile(r"license|licence|copying|notice|copyright|third[ _-]?party", re.I)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def relative(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.name


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def elf_properties(data: bytes) -> dict:
    """Inspect every ELF64 PT_LOAD header, including offset/address congruence."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ValueError("Missing or truncated ELF header")
    elf_class, encoding = data[4], data[5]
    if elf_class != 2:
        raise ValueError(f"Expected ELF64, found ELF class {elf_class}")
    if encoding not in (1, 2):
        raise ValueError("Invalid ELF byte order")
    endian = "<" if encoding == 1 else ">"
    ph_offset = struct.unpack_from(endian + "Q", data, 32)[0]
    ph_size, ph_count = struct.unpack_from(endian + "HH", data, 54)
    if ph_size < 56 or ph_count == 0 or ph_count == 0xFFFF:
        raise ValueError("Unsupported/missing ELF program header table")
    if ph_offset + ph_size * ph_count > len(data):
        raise ValueError("ELF program headers exceed file size")
    loads = []
    for index in range(ph_count):
        values = struct.unpack_from(endian + "IIQQQQQQ", data, ph_offset + index * ph_size)
        kind, flags, offset, address, _, file_size, memory_size, alignment = values
        if kind != 1:
            continue
        loads.append({"index": index, "flags": flags, "offset": offset, "virtual_address": address,
                      "file_size": file_size, "memory_size": memory_size, "alignment": alignment,
                      "aligned_16k": alignment >= ALIGNMENT and alignment & (alignment - 1) == 0
                      and offset % alignment == address % alignment,
                      "file_range_valid": offset + file_size <= len(data)})
    if not loads:
        raise ValueError("ELF has no PT_LOAD segments")
    return {"elf_class": 64, "byte_order": "little" if encoding == 1 else "big",
            "machine": struct.unpack_from(endian + "H", data, 18)[0], "load_segments": loads,
            "all_load_segments_aligned_16k": all(p["aligned_16k"] and p["file_range_valid"] for p in loads)}


def elf_identity(data: bytes) -> dict:
    """Identity survives debug-symbol stripping; retain both GNU build ID and allocated-section digest."""
    properties = elf_properties(data)
    endian = "<" if data[5] == 1 else ">"
    ph_offset = struct.unpack_from(endian + "Q", data, 32)[0]
    ph_size, ph_count = struct.unpack_from(endian + "HH", data, 54)
    build_ids = set()
    for index in range(ph_count):
        kind, _, offset, _, _, size, _, _ = struct.unpack_from(endian + "IIQQQQQQ", data, ph_offset + index * ph_size)
        if kind != 4:
            continue
        end = offset + size
        if end > len(data):
            raise ValueError("ELF note exceeds file size")
        while offset + 12 <= end:
            name_size, value_size, note_type = struct.unpack_from(endian + "III", data, offset)
            offset += 12
            name = data[offset:offset + name_size]
            offset += (name_size + 3) & ~3
            value = data[offset:offset + value_size]
            offset += (value_size + 3) & ~3
            if offset > end:
                raise ValueError("Truncated ELF note")
            if name.rstrip(b"\0") == b"GNU" and note_type == 3:
                build_ids.add(value.hex())
    section_offset = struct.unpack_from(endian + "Q", data, 40)[0]
    section_size, section_count, names_index = struct.unpack_from(endian + "HHH", data, 58)
    allocated = []
    if section_offset and section_count and section_size >= 64 and names_index < section_count:
        if section_offset + section_size * section_count > len(data):
            raise ValueError("ELF section table exceeds file size")
        sections = [struct.unpack_from(endian + "IIQQQQIIQQ", data, section_offset + index * section_size)
                    for index in range(section_count)]
        names_section = sections[names_index]
        names = data[names_section[4]:names_section[4] + names_section[5]]
        for name_offset, kind, flags, address, offset, size, _, _, _, _ in sections:
            if flags & 2 == 0:
                continue
            name_end = names.find(b"\0", name_offset)
            if name_end < 0:
                raise ValueError("Unterminated ELF section name")
            if kind != 8 and offset + size > len(data):
                raise ValueError("ELF allocated section exceeds file size")
            allocated.append({"name": names[name_offset:name_end].decode("utf-8"), "type": kind,
                              "flags": flags, "address": address, "size": size,
                              "content_sha256": None if kind == 8 else digest(data[offset:offset + size])})
    return {"machine": properties["machine"], "gnu_build_id": next(iter(build_ids)) if len(build_ids) == 1 else None,
            "allocated_sections_sha256": digest(json.dumps(sorted(allocated, key=lambda item: (item["address"], item["name"])),
                                                          sort_keys=True).encode()) if allocated else None,
            "allocated_section_count": len(allocated)}


def native_match(source: dict, packaged: dict) -> str | None:
    if source["identity"]["machine"] != packaged["identity"]["machine"]:
        return None
    if source["sha256"] == packaged["sha256"]:
        return "exact_sha256"
    first, second = source["identity"], packaged["identity"]
    if first["allocated_sections_sha256"] and first["allocated_sections_sha256"] == second["allocated_sections_sha256"]:
        return "normalized_allocated_sections_sha256"
    if first["gnu_build_id"] and first["gnu_build_id"] == second["gnu_build_id"]:
        return "gnu_build_id"
    return None


def zip_data_offset(apk: Path, entry: zipfile.ZipInfo) -> int:
    with apk.open("rb") as source:
        source.seek(entry.header_offset)
        header = source.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError("Missing native ZIP local file header")
    name_length, extra_length = struct.unpack_from("<HH", header, 26)
    return entry.header_offset + 30 + name_length + extra_length


def _length8(data: bytes, offset: int) -> tuple[int, int]:
    first = data[offset]
    if first & 0x80:
        return ((first & 0x7F) << 8) | data[offset + 1], offset + 2
    return first, offset + 1


def _length16(data: bytes, offset: int) -> tuple[int, int]:
    first = struct.unpack_from("<H", data, offset)[0]
    if first & 0x8000:
        second = struct.unpack_from("<H", data, offset + 2)[0]
        return ((first & 0x7FFF) << 16) | second, offset + 4
    return first, offset + 2


def apk_permissions(data: bytes) -> list[str]:
    """Read requested permissions from Android binary XML, not an ASCII substring guess."""
    if data.lstrip().startswith(b"<"):
        return xml_permissions(data)
    if len(data) < 8:
        raise ValueError("Truncated APK manifest")
    kind, header_size, total_size = struct.unpack_from("<HHI", data)
    if kind != 3 or header_size < 8 or total_size != len(data):
        raise ValueError("Invalid Android binary XML header")
    strings: list[str] = []
    permissions = []
    position = header_size
    while position < total_size:
        if position + 8 > total_size:
            raise ValueError("Truncated XML chunk")
        chunk_kind, chunk_header, chunk_size = struct.unpack_from("<HHI", data, position)
        if chunk_header < 8 or chunk_size < chunk_header or position + chunk_size > total_size:
            raise ValueError("Invalid XML chunk bounds")
        chunk = data[position:position + chunk_size]
        if chunk_kind == 1:
            if chunk_header < 28:
                raise ValueError("Invalid XML string pool")
            count, _, flags, strings_start, _ = struct.unpack_from("<IIIII", chunk, 8)
            if chunk_header + count * 4 > len(chunk):
                raise ValueError("Invalid string offset table")
            strings = []
            for index in range(count):
                offset = strings_start + struct.unpack_from("<I", chunk, chunk_header + index * 4)[0]
                if flags & 0x100:
                    _, offset = _length8(chunk, offset)
                    length, offset = _length8(chunk, offset)
                    value = chunk[offset:offset + length].decode("utf-8")
                    if offset + length >= len(chunk) or chunk[offset + length] != 0:
                        raise ValueError("Unterminated UTF-8 manifest string")
                else:
                    length, offset = _length16(chunk, offset)
                    value = chunk[offset:offset + length * 2].decode("utf-16-le")
                    if offset + length * 2 + 2 > len(chunk) or chunk[offset + length * 2:offset + length * 2 + 2] != b"\0\0":
                        raise ValueError("Unterminated UTF-16 manifest string")
                strings.append(value)
        elif chunk_kind == 0x102:
            extension = chunk_header
            if extension + 20 > len(chunk):
                raise ValueError("Truncated XML start element")
            name = strings[struct.unpack_from("<I", chunk, extension + 4)[0]]
            start, attribute_size, count = struct.unpack_from("<HHH", chunk, extension + 8)
            if attribute_size < 20 or extension + start + attribute_size * count > len(chunk):
                raise ValueError("Invalid manifest attribute table")
            if name.startswith("uses-permission"):
                for index in range(count):
                    attribute = extension + start + index * attribute_size
                    attribute_name = strings[struct.unpack_from("<I", chunk, attribute + 4)[0]]
                    raw_index = struct.unpack_from("<I", chunk, attribute + 8)[0]
                    value_type = chunk[attribute + 15]
                    value_index = struct.unpack_from("<I", chunk, attribute + 16)[0]
                    if attribute_name == "name":
                        if raw_index != 0xFFFFFFFF:
                            permissions.append(strings[raw_index])
                        elif value_type == 3:
                            permissions.append(strings[value_index])
                        else:
                            raise ValueError("Permission name has no string value")
        position += chunk_size
    return sorted(set(permissions))


def xml_permissions(data: bytes) -> list[str]:
    manifest = ET.fromstring(data)
    return sorted({node.attrib[ANDROID_NAME] for node in manifest
                   if node.tag.startswith("uses-permission") and ANDROID_NAME in node.attrib})


def inspect_aar(aar: Path, root: Path) -> dict:
    notices_dir = root / "third-party" / "opencv-aar-notices"
    notices_dir.mkdir(parents=True, exist_ok=True)
    notices, inspected_archives, native = [], [], []

    def visit(archive: zipfile.ZipFile, chain: str = "", depth: int = 0) -> None:
        inspected_archives.append({"archive": chain or aar.name, "entry_count": len(archive.infolist())})
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in entry.filename or ":" in entry.filename:
                raise ValueError(f"Unsafe AAR entry path: {entry.filename}")
            source_name = f"{chain}!/{entry.filename}" if chain else entry.filename
            if NOTICE_NAME.search(path.name):
                content = archive.read(entry)
                target = notices_dir / (chain.replace("!/", "__embedded/") + "__embedded" if chain else "") / Path(*path.parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
                notices.append({"source_entry": source_name, "saved_path": relative(target, root),
                                "size_bytes": len(content), "sha256": digest(content)})
            if entry.filename.endswith((".jar", ".zip")) and depth < 3:
                content = archive.read(entry)
                if zipfile.is_zipfile(io.BytesIO(content)):
                    with zipfile.ZipFile(io.BytesIO(content)) as embedded:
                        visit(embedded, source_name, depth + 1)
            if not chain and entry.filename.startswith("jni/") and entry.filename.endswith(".so"):
                content = archive.read(entry)
                item = {"aar_entry": entry.filename, "abi": path.parts[1], "name": path.name,
                        "size_bytes": len(content), "sha256": digest(content),
                        "license_review": "pending", "packaged_notice_entries": [n["source_entry"] for n in notices]}
                if content[4:5] == b"\x02":
                    item.update(elf_properties(content))
                else:
                    item["elf_class"] = 32 if content[4:5] == b"\x01" else "unknown"
                native.append(item)

    with zipfile.ZipFile(aar) as archive:
        visit(archive)
    notice_names = [item["source_entry"] for item in notices]
    for item in native:
        item["packaged_notice_entries"] = notice_names
    inventory = {"artifact": f"org.opencv:opencv:{OPENCV_VERSION}", "aar_filename": aar.name,
                 "aar_sha256": file_digest(aar), "aar_size_bytes": aar.stat().st_size,
                 "inspected_archives": inspected_archives, "extracted_notices": notices,
                 "native_libraries": native,
                 "license_review": {"status": "pending", "embedded_notices_found": bool(notices),
                     "gaps": ["Audit does not identify every statically linked third-party component or its license obligations.",
                              "Review OpenCV build provenance and full upstream/transitive native notices before distribution.",
                              "Review libc++_shared.so licensing and its complete upstream license/notice text."] +
                             ([] if notices else ["The inspected AAR and embedded archives contain no matching license, notice, copyright, COPYING, or third-party notice filenames."])} }
    write_json(notices_dir / "extraction-index.json", {"aar_sha256": inventory["aar_sha256"],
               "inspected_archives": inspected_archives, "extracted_notices": notices,
               "license_review_status": "pending"})
    write_json(root / "third-party" / "native-inventory.json", inventory)
    return inventory


def collect_native_candidates(root: Path) -> tuple[list[dict], dict, list[str]]:
    candidates, gaps = [], []
    manifest_path = root / "evidence/native-build-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    if manifest.get("openCvVersion") != OPENCV_VERSION:
        gaps.append("Native source manifest has an unexpected OpenCV version")
    module_set = {"opencv_core", "opencv_imgproc", "opencv_java_bindings_generator", "opencv_java"}
    for artifact in manifest["artifacts"]:
        abi = artifact["abi"]
        if set(artifact["modules"]) != module_set:
            gaps.append(f"Unexpected source-built OpenCV module set for {abi}: {artifact['modules']}")
        for role in ("opencv", "runtime"):
            recorded = artifact[role]
            source = root / "scanner-processing-opencv/src/main/jniLibs" / abi / recorded["file"]
            if not source.is_file():
                gaps.append(f"Source-built binary missing: {relative(source, root)}")
                continue
            content = source.read_bytes()
            if role == "opencv" and re.search(rb"(?i)[a-z]:[/\\]Users[/\\]", content):
                gaps.append(f"Personal build-machine path embedded in source-built binary: {relative(source, root)}")
                continue
            actual_hash = digest(content)
            if actual_hash != recorded["sha256"]:
                gaps.append(f"Native build manifest SHA256 mismatch: {relative(source, root)}")
                continue
            identity = elf_identity(content)
            if identity["machine"] != {"arm64-v8a": 183, "x86_64": 62}.get(abi):
                gaps.append(f"Wrong architecture in source-built binary: {relative(source, root)}")
                continue
            candidates.append({"kind": "source_build", "role": role, "abi": abi, "name": recorded["file"],
                               "source_path": relative(source, root), "sha256": actual_hash, "size_bytes": len(content),
                               "identity": identity, "manifest_record": recorded,
                               "source_archive_sha256": manifest["sourceArchiveSha256"], "modules": artifact["modules"],
                               "notices": [], "pom_licenses": []})
    artifacts_path = root / "evidence/runtime-artifacts.tsv"
    seen = set()
    for row in artifacts_path.read_text(encoding="utf-8-sig").splitlines():
        fields = row.split("\t")
        if len(fields) != 4:
            continue
        group, name, version, filename = fields
        archive_path = Path(filename)
        if archive_path.suffix != ".aar" or filename in seen:
            continue
        seen.add(filename)
        if not archive_path.is_file():
            # Resolve the cache path again if the project was moved to another host.
            choices = list((Path.home() / ".gradle/caches/modules-2/files-2.1" / group / name / version).glob("*/*.aar"))
            if not choices:
                gaps.append(f"Resolved runtime artifact is unavailable: {group}:{name}:{version}")
                continue
            archive_path = choices[0]
        with zipfile.ZipFile(archive_path) as archive:
            native_entries = [entry for entry in archive.infolist() if re.fullmatch(r"jni/(arm64-v8a|x86_64)/[^/]+\.so", entry.filename)]
            if not native_entries:
                continue
            notices = [{"entry": entry.filename, "sha256": digest(archive.read(entry)), "size_bytes": entry.file_size}
                       for entry in archive.infolist() if not entry.is_dir() and NOTICE_NAME.search(PurePosixPath(entry.filename).name)]
            pom_licenses = []
            poms = sorted(archive_path.parent.parent.glob("*/*.pom"))
            if poms:
                pom = ET.fromstring(poms[0].read_bytes())
                pom_licenses = [{"name": license.findtext("{*}name", ""), "url": license.findtext("{*}url", "")}
                                for license in pom.findall(".//{*}license")]
            archive_hash = file_digest(archive_path)
            for entry in native_entries:
                content = archive.read(entry)
                candidates.append({"kind": "resolved_dependency", "coordinate": f"{group}:{name}:{version}",
                                   "archive_sha256": archive_hash, "archive_filename": archive_path.name,
                                   "source_entry": entry.filename, "abi": entry.filename.split("/")[1],
                                   "name": PurePosixPath(entry.filename).name, "sha256": digest(content),
                                   "size_bytes": len(content), "identity": elf_identity(content),
                                   "notices": notices, "pom_licenses": pom_licenses})
    manifest_evidence = {"path": relative(manifest_path, root), "sha256": file_digest(manifest_path),
                         "source_archive_sha256": manifest["sourceArchiveSha256"], "source_url": manifest["sourceUrl"],
                         "ndk_version": manifest["ndkVersion"], "android_api": manifest["androidApi"],
                         "runtime_artifacts_sha256": file_digest(artifacts_path)}
    source_archive = root.parents[1] / "work/native" / f"opencv-{OPENCV_VERSION}.zip"
    if source_archive.is_file():
        manifest_evidence["source_archive_available_and_hash_verified"] = file_digest(source_archive) == manifest["sourceArchiveSha256"]
        if not manifest_evidence["source_archive_available_and_hash_verified"]:
            gaps.append("OpenCV source archive hash differs from native build manifest")
    return candidates, manifest_evidence, gaps


def reconcile_native(root: Path, apk: Path, libraries: list[dict], candidates: list[dict], build: dict,
                     initial_gaps: list[str]) -> dict:
    gaps = list(initial_gaps)
    required_source_notices = ("OpenCV-LICENSE.txt", "OpenCV-core-imgproc-java-SOURCE-NOTICES.txt",
                               "Android-NDK-NOTICE.txt", "Android-NDK-NOTICE.toolchain.txt")
    with zipfile.ZipFile(apk) as archive:
        apk_notices = []
        for entry in archive.infolist():
            if not entry.is_dir() and NOTICE_NAME.search(PurePosixPath(entry.filename).name) and entry.file_size < 20_000_000:
                data = archive.read(entry)
                apk_notices.append({"entry": entry.filename, "sha256": digest(data), "size_bytes": len(data),
                                    "is_full_apache_text": b"Apache License" in data and b"END OF TERMS AND CONDITIONS" in data})
        packaged_hashes = {notice["sha256"]: notice["entry"] for notice in apk_notices}
        source_notices = []
        for name in required_source_notices:
            source = root / "third-party/native-source-notices" / name
            if not source.is_file():
                gaps.append("Native source notice missing: " + name)
                continue
            value = {"path": relative(source, root), "sha256": file_digest(source), "size_bytes": source.stat().st_size}
            value["apk_entry"] = packaged_hashes.get(value["sha256"])
            if value["apk_entry"] is None:
                gaps.append("Full native source notice is absent/changed in APK: " + name)
            source_notices.append(value)
        source_inventory = root / "third-party/native-source-notices/inventory.json"
        source_collection = {"path": relative(source_inventory, root), "sha256": file_digest(source_inventory)}
        indexed = json.loads(source_inventory.read_text(encoding="utf-8-sig"))
        source_collection["indexed_file_count"] = len(indexed)
        source_collection["notice_block_count"] = sum(item["noticeBlockCount"] for item in indexed)
        source_text = (root / "third-party/native-source-notices/OpenCV-core-imgproc-java-SOURCE-NOTICES.txt").read_text(encoding="utf-8-sig")
        missing_paths = [item["sourcePath"] for item in indexed if "FILE: " + item["sourcePath"] not in source_text]
        if missing_paths:
            gaps.append("Collected source notices omit indexed files: " + ", ".join(missing_paths))
        source_collection["all_indexed_files_in_notice_collection"] = not missing_paths
        libyuv_files = [path for path in (root / "third-party").rglob("*") if path.is_file() and "libyuv" in path.name.lower()]
        libyuv_notices = []
        for path in libyuv_files:
            data = path.read_bytes()
            full_bsd = all(term in data.lower() for term in (b"copyright", b"redistribution and use", b"this software is provided"))
            libyuv_notices.append({"path": relative(path, root), "sha256": digest(data),
                                  "apk_entry": packaged_hashes.get(digest(data)), "full_bsd_notice": full_bsd})
        reconciled = []
        for library in libraries:
            library_gap_start = len(gaps)
            matched = [(candidate, native_match(candidate, library)) for candidate in candidates
                       if candidate["abi"] == library["abi"] and candidate["name"] == library["name"]]
            matched = [(candidate, method) for candidate, method in matched if method]
            record = {"apk_entry": library["entry"], "abi": library["abi"], "name": library["name"],
                      "packaged_sha256": library["sha256"], "packaged_size_bytes": library["size_bytes"],
                      "packaged_identity": library["identity"], "notice_evidence": []}
            if not matched:
                record["provenance_status"] = "unmapped"
                gaps.append("Packaged native library has no verified source/resolved-archive match: " + library["entry"])
            else:
                candidate, method = matched[0]
                record["provenance_status"] = "reconciled"
                record["match_method"] = method
                record["source"] = {key: value for key, value in candidate.items() if key not in ("notices", "pom_licenses")}
                if candidate["kind"] == "source_build":
                    wanted = required_source_notices[:2] if candidate["role"] == "opencv" else required_source_notices[2:]
                    record["notice_evidence"] = [notice for notice in source_notices if PurePosixPath(notice["path"]).name in wanted]
                else:
                    record["declared_pom_licenses"] = candidate["pom_licenses"]
                    for notice in candidate["notices"]:
                        packaged_entry = packaged_hashes.get(notice["sha256"])
                        record["notice_evidence"].append({**notice, "apk_entry": packaged_entry})
                        if packaged_entry is None:
                            gaps.append(f"Embedded dependency notice is absent/changed in APK: {candidate['coordinate']} {notice['entry']}")
                    apache_declared = any("apache" in license["name"].lower() for license in candidate["pom_licenses"])
                    if apache_declared and not candidate["notices"]:
                        apache_text = next((notice for notice in apk_notices if notice["is_full_apache_text"]), None)
                        if apache_text:
                            record["notice_evidence"].append({**apache_text, "apk_entry": apache_text["entry"],
                                                             "basis": "POM Apache-2.0 declaration and full packaged Apache license text"})
                        else:
                            gaps.append("Full declared Apache license text missing for " + candidate["coordinate"])
                    if any("bsd" in license["name"].lower() or "libyuv" in license["url"].lower() for license in candidate["pom_licenses"]):
                        full_libyuv = [notice for notice in libyuv_notices if notice["apk_entry"] and notice["full_bsd_notice"]]
                        record["notice_evidence"].extend(full_libyuv)
                        if not full_libyuv:
                            gaps.append("CameraX POM declares BSD libyuv; full libyuv notice is not verified in APK")
            record["mapping_gaps"] = gaps[library_gap_start:]
            record["notice_status"] = "evidence_collected" if not record["mapping_gaps"] and record["notice_evidence"] and all(n.get("apk_entry") for n in record["notice_evidence"]) else "gap"
            reconciled.append(record)
    previous_path = root / "third-party/native-inventory.json"
    previous = json.loads(previous_path.read_text(encoding="utf-8")) if previous_path.is_file() else {}
    historical = previous.get("historical_stock_aar") or {
        "usage": "historical_inspection_only_not_final_runtime", "artifact": previous.get("artifact"),
        "aar_sha256": previous.get("aar_sha256"), "inspected_archives": previous.get("inspected_archives"),
        "extracted_notices": previous.get("extracted_notices", []),
        "native_library_hashes": [{key: value for key, value in native.items() if key in ("aar_entry", "abi", "name", "size_bytes", "sha256")}
                                  for native in previous.get("native_libraries", [])]}
    inventory = {"schema_version": 2, "runtime_baseline": "source_built_opencv_core_imgproc_java_and_resolved_androidx",
                 "native_build": build, "source_notice_collection": source_collection, "source_notices": source_notices,
                 "inspected_apk_sha256": file_digest(apk), "apk_native_libraries": reconciled,
                 "historical_stock_aar": historical, "mapping_gaps": sorted(set(gaps)),
                 "license_review_status": "evidence_collected_reconciled" if not gaps else "specific_gaps_remaining",
                 "notice_source_limitations": ["CameraX POM declares libyuv BSD licensing and an upstream main-branch URL, but does not encode the exact vendored libyuv revision. This audit verifies preservation of the collected full upstream notice, not an exact source-revision determination."],
                 "scope": "Provenance and preservation of collected license/notice evidence; not a blanket legal compliance certification."}
    write_json(previous_path, inventory)
    return inventory


def audit(root: Path, apk: Path, graph: Path, aar: Path | None) -> dict:
    errors: list[str] = []
    inventory = None
    native_candidates, build_evidence, native_gaps = [], {}, []
    report = {"schema_version": 2, "created_utc": datetime.now(timezone.utc).isoformat(),
              "required_native_alignment": ALIGNMENT, "errors": errors,
              "limitations": ["Static artifact checks do not verify camera behavior, OpenCV execution, or Android 16 KB device runtime behavior.",
                              "Reconciliation verifies native provenance and preservation of collected notice evidence; it is not a blanket legal compliance certification."]}
    try:
        native_candidates, build_evidence, native_gaps = collect_native_candidates(root)
        report["native_build"] = build_evidence
        report["historical_opencv_aar"] = {"usage": "historical_inspection_only_not_final_runtime",
                                           "extraction_index": "third-party/opencv-aar-notices/extraction-index.json"}
    except Exception as error:
        errors.append(f"Source-built native provenance evidence failed: {error}")
        native_gaps.append(str(error))
    if not graph.is_file():
        errors.append("Resolved dependency graph is missing")
    else:
        graph_bytes = graph.read_bytes()
        try:
            text = graph_bytes.decode("utf-16" if graph_bytes.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")
        except UnicodeDecodeError as error:
            errors.append(f"Cannot decode dependency graph: {error}")
            text = ""
        matches = re.findall(r"(?<![\w.])([\w.-]+):([\w.-]+):([\w.+~-]+)", text)
        coordinates = sorted({":".join(parts) for parts in matches})
        forbidden = sorted({coordinate for coordinate in coordinates if any(coordinate.startswith(group + ":") for group in FORBIDDEN_GROUPS)})
        report["dependencies"] = {"path": relative(graph, root), "sha256": file_digest(graph),
                                  "coordinates_found": coordinates, "forbidden_coordinates": forbidden,
                                  "configuration": "debugRuntimeClasspath"}
        if "debugRuntimeClasspath" not in text or not coordinates:
            errors.append("Dependency evidence does not contain a resolved debugRuntimeClasspath graph")
        if re.search(r"\bFAILED\b|Could not resolve|BUILD FAILED", text):
            errors.append("Dependency graph contains unresolved/failed dependencies")
        if any(coordinate.startswith("org.opencv:opencv:") for coordinate in coordinates):
            errors.append("Resolved graph includes stock OpenCV AAR despite source-built native baseline")
        java_jar = root / "scanner-processing-opencv/libs/opencv-java-4.12.0.jar"
        if java_jar.is_file():
            report["dependencies"]["source_built_opencv_java_jar"] = {"path": relative(java_jar, root), "sha256": file_digest(java_jar)}
        else:
            errors.append("Source-built OpenCV Java binding jar missing")
        if forbidden:
            errors.append("Play-services/Firebase/MLKit/Google on-device ML/Play dependency present: " + ", ".join(forbidden))
    merged = sorted(path for path in root.glob("scanner-*/build/intermediates/**/AndroidManifest.xml")
                    if any("merged_manifest" in part for part in path.parts))
    report["merged_manifests"] = []
    if not any(path.relative_to(root).parts[0] == "scanner-sample" for path in merged):
        errors.append("Sample merged manifest is missing")
    for path in merged:
        try:
            permissions = xml_permissions(path.read_bytes())
            report["merged_manifests"].append({"path": relative(path, root), "sha256": file_digest(path), "permissions": permissions})
            if "android.permission.INTERNET" in permissions:
                errors.append("INTERNET permission in " + relative(path, root))
        except Exception as error:
            errors.append(f"Cannot parse merged manifest {relative(path, root)}: {error}")
    if not apk.is_file():
        errors.append("Sample APK is missing")
    else:
        report["apk"] = {"path": relative(apk, root), "size_bytes": apk.stat().st_size,
                         "sha256": file_digest(apk), "native_libraries": []}
        try:
            with zipfile.ZipFile(apk) as archive:
                try:
                    permissions = apk_permissions(archive.read("AndroidManifest.xml"))
                    report["apk"]["permissions"] = permissions
                    if "android.permission.INTERNET" in permissions:
                        errors.append("INTERNET permission in actual APK binary manifest")
                except Exception as error:
                    errors.append(f"APK manifest inspection failed: {error}")
                manifest_entry = "assets/docquad/model-manifest.json"
                try:
                    manifest_data = archive.read(manifest_entry)
                    model_manifest = json.loads(manifest_data)
                    model_bytes = archive.read("assets/" + model_manifest["asset"])
                    model_record = {"manifest_entry": manifest_entry, "manifest_sha256": digest(manifest_data),
                                    "model_id": model_manifest["modelId"], "source_revision": model_manifest["sourceRevision"],
                                    "sha256": digest(model_bytes), "size_bytes": len(model_bytes), "notices": []}
                    if digest(model_bytes) != model_manifest["sha256"] or len(model_bytes) != model_manifest["sizeBytes"]:
                        errors.append("Bundled learned model differs from pinned manifest")
                    if model_manifest.get("networkRequired") is not False or model_manifest.get("defaultDetector") is not True:
                        errors.append("Learned-model offline/default policy differs from reviewed candidate")
                    asset_root = root / "scanner-processing-opencv/src/main/assets"
                    if (asset_root / "docquad/model-manifest.json").read_bytes() != manifest_data:
                        errors.append("Packaged learned-model manifest differs from source")
                    for owner, fields in (("model", model_manifest), ("runtime", model_manifest["runtime"])):
                        for key in ("licensePath", "noticePath", "licenseReviewPath"):
                            if key not in fields:
                                continue
                            entry = "assets/" + fields[key]
                            packaged = archive.read(entry)
                            if not packaged or packaged != (asset_root / fields[key]).read_bytes():
                                errors.append("Learned-model/runtime attribution missing or changed: " + entry)
                            model_record["notices"].append({"owner": owner, "entry": entry, "sha256": digest(packaged)})
                    report["learned_model"] = model_record
                except Exception as error:
                    errors.append("Learned-model integrity/attribution audit failed: " + str(error))
                seen_names = set()
                for entry in archive.infolist():
                    if entry.filename in seen_names:
                        errors.append("Duplicate APK ZIP entry: " + entry.filename)
                    seen_names.add(entry.filename)
                    if not re.fullmatch(r"lib/[^/]+/[^/]+\.so", entry.filename):
                        continue
                    content = archive.read(entry)
                    offset = zip_data_offset(apk, entry)
                    item = {"entry": entry.filename, "abi": entry.filename.split("/")[1], "name": PurePosixPath(entry.filename).name,
                            "size_bytes": len(content), "compressed_size_bytes": entry.compress_size,
                            "sha256": digest(content), "compression_method": entry.compress_type,
                            "uncompressed": entry.compress_type == zipfile.ZIP_STORED,
                            "zip_data_offset": offset, "zip_data_aligned_16k": offset % ALIGNMENT == 0}
                    try:
                        item.update(elf_properties(content))
                        item["identity"] = elf_identity(content)
                        if not item["all_load_segments_aligned_16k"]:
                            errors.append("ELF PT_LOAD segment alignment/range failed: " + entry.filename)
                    except Exception as error:
                        item["elf_error"] = str(error)
                        errors.append(f"Native ELF inspection failed for {entry.filename}: {error}")
                    if not item["uncompressed"]:
                        errors.append("Native library is compressed in APK: " + entry.filename)
                    if not item["zip_data_aligned_16k"]:
                        errors.append("Native ZIP data offset is not 16 KB aligned: " + entry.filename)
                    report["apk"]["native_libraries"].append(item)
                report["apk"]["abis"] = sorted({item["abi"] for item in report["apk"]["native_libraries"]})
                if not report["apk"]["native_libraries"]:
                    errors.append("APK contains no native libraries")
                if set(report["apk"]["abis"]) != {"arm64-v8a", "x86_64"}:
                    errors.append("Sample native ABI set differs from configured arm64-v8a/x86_64")
                for abi in ("arm64-v8a", "x86_64"):
                    packaged_names = {item["name"] for item in report["apk"]["native_libraries"] if item["abi"] == abi}
                    missing = {"libopencv_java4.so", "libc++_shared.so"} - packaged_names
                    if missing:
                        errors.append(f"Required OpenCV native libraries missing for {abi}: " + ", ".join(sorted(missing)))
        except Exception as error:
            errors.append(f"APK inspection failed: {error}")
    if "apk" in report:
        try:
            inventory = reconcile_native(root, apk, report["apk"]["native_libraries"], native_candidates, build_evidence, native_gaps)
            report["native_provenance"] = {"inventory": "third-party/native-inventory.json",
                "mapping_gaps": inventory["mapping_gaps"], "packaged_native_count": len(inventory["apk_native_libraries"]),
                "verified_native_count": sum(item["provenance_status"] == "reconciled" for item in inventory["apk_native_libraries"]),
                "source_notice_file_count": inventory["source_notice_collection"]["indexed_file_count"]}
            errors.extend(inventory["mapping_gaps"])
        except Exception as error:
            errors.append(f"Native provenance/notice reconciliation failed: {error}")
    report["status"] = "passed" if not errors else "failed"
    report["license_review_status"] = inventory["license_review_status"] if inventory is not None else "specific_gaps_remaining"
    write_json(root / "evidence" / "release-audit.json", report)
    return report


class AuditSelfTests(unittest.TestCase):
    def elf(self, alignment: int) -> bytes:
        result = bytearray(120)
        result[:7] = b"\x7fELF\x02\x01\x01"
        struct.pack_into("<H", result, 18, 183)
        struct.pack_into("<Q", result, 32, 64)
        struct.pack_into("<HH", result, 54, 56, 1)
        struct.pack_into("<IIQQQQQQ", result, 64, 1, 5, 0, 0, 0, 120, 120, alignment)
        return bytes(result)

    def test_elf_accepts_16k_and_rejects_4k_and_truncation(self):
        self.assertTrue(elf_properties(self.elf(16384))["all_load_segments_aligned_16k"])
        self.assertFalse(elf_properties(self.elf(4096))["all_load_segments_aligned_16k"])
        with self.assertRaises(ValueError):
            elf_properties(self.elf(16384)[:80])

    def test_elf_requires_address_offset_congruence(self):
        result = bytearray(self.elf(16384))
        struct.pack_into("<Q", result, 64 + 16, 4096)
        self.assertFalse(elf_properties(bytes(result))["all_load_segments_aligned_16k"])

    def section_fixture(self, debug: bool, code: bytes = b"code") -> bytes:
        names = b"\0.text\0.debug\0.shstrtab\0"
        debug_bytes = b"debug" if debug else b""
        header = bytearray(self.elf(16384))
        string_offset = len(header) + len(code) + len(debug_bytes)
        payload = bytes(header) + code + debug_bytes + names
        payload += b"\0" * (-len(payload) % 8)
        section_offset = len(payload)
        sections = [bytes(64), struct.pack("<IIQQQQIIQQ", 1, 1, 6, 4096, 120, len(code), 0, 0, 4, 0)]
        if debug:
            sections.append(struct.pack("<IIQQQQIIQQ", 7, 1, 0, 0, 124, len(debug_bytes), 0, 0, 1, 0))
        sections.append(struct.pack("<IIQQQQIIQQ", 14, 3, 0, 0, string_offset, len(names), 0, 0, 1, 0))
        result = bytearray(payload + b"".join(sections))
        struct.pack_into("<Q", result, 40, section_offset)
        struct.pack_into("<HHH", result, 58, 64, len(sections), len(sections) - 1)
        return bytes(result)

    def test_stripping_changes_raw_hash_but_preserves_allocated_native_identity(self):
        source, stripped = self.section_fixture(True), self.section_fixture(False)
        self.assertNotEqual(digest(source), digest(stripped))
        first = {"sha256": digest(source), "identity": elf_identity(source)}
        second = {"sha256": digest(stripped), "identity": elf_identity(stripped)}
        self.assertEqual("normalized_allocated_sections_sha256", native_match(first, second))
        changed = self.section_fixture(False, b"c0de")
        self.assertIsNone(native_match(first, {"sha256": digest(changed), "identity": elf_identity(changed)}))

    def test_gnu_build_id_survives_nonload_debug_bytes(self):
        result = bytearray(212)
        result[:64] = self.elf(16384)[:64]
        struct.pack_into("<H", result, 56, 2)
        struct.pack_into("<IIQQQQQQ", result, 64, 1, 5, 0, 0, 0, 212, 212, 16384)
        struct.pack_into("<IIQQQQQQ", result, 120, 4, 4, 176, 176, 176, 36, 36, 4)
        result[176:] = struct.pack("<III", 4, 20, 3) + b"GNU\0" + bytes(range(20))
        first = elf_identity(bytes(result))
        self.assertEqual(bytes(range(20)).hex(), first["gnu_build_id"])
        extended = bytes(result) + b"nonallocated debug symbols"
        self.assertEqual("gnu_build_id", native_match({"sha256": digest(result), "identity": first},
                                                     {"sha256": digest(extended), "identity": elf_identity(extended)}))

    def binary_manifest(self, utf8: bool) -> bytes:
        values = ["uses-permission", "name", "android.permission.INTERNET"]
        encoded = [bytes([len(value), len(value)]) + value.encode() + b"\0" if utf8 else
                   struct.pack("<H", len(value)) + value.encode("utf-16-le") + b"\0\0" for value in values]
        string_data = b"".join(encoded)
        string_data += b"\0" * (-len(string_data) % 4)
        offsets = [0, len(encoded[0]), len(encoded[0]) + len(encoded[1])]
        pool = struct.pack("<HHIIIIII", 1, 28, 40 + len(string_data), 3, 0, 0x100 if utf8 else 0, 40, 0)
        pool += struct.pack("<III", *offsets) + string_data
        element = struct.pack("<HHIII", 0x102, 16, 56, 1, 0xFFFFFFFF)
        element += struct.pack("<IIHHHHHH", 0xFFFFFFFF, 0, 20, 20, 1, 0, 0, 0)
        element += struct.pack("<IIIHBBI", 0xFFFFFFFF, 1, 2, 8, 0, 3, 2)
        return struct.pack("<HHI", 3, 8, 8 + len(pool) + len(element)) + pool + element

    def test_binary_xml_reads_utf8_permission_attribute(self):
        self.assertEqual(["android.permission.INTERNET"], apk_permissions(self.binary_manifest(True)))

    def test_binary_xml_reads_utf16_permission_attribute(self):
        self.assertEqual(["android.permission.INTERNET"], apk_permissions(self.binary_manifest(False)))

    def test_native_zip_offset_includes_local_name_and_extra_padding(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / "fixture.apk"
            entry = zipfile.ZipInfo("lib/arm64-v8a/fixture.so")
            entry.compress_type = zipfile.ZIP_STORED
            extra_length = ALIGNMENT - 30 - len(entry.filename.encode())
            entry.extra = struct.pack("<HH", 0xCAFE, extra_length - 4) + b"\0" * (extra_length - 4)
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(entry, b"payload")
            with zipfile.ZipFile(apk) as archive:
                offset = zip_data_offset(apk, archive.getinfo(entry.filename))
            self.assertEqual(ALIGNMENT, offset)
            with apk.open("rb") as source:
                source.seek(offset)
                self.assertEqual(b"payload", source.read(7))

    def test_text_manifest_detects_internet(self):
        value = b'<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.INTERNET"/></manifest>'
        self.assertEqual(["android.permission.INTERNET"], xml_permissions(value))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--dependency-graph", type=Path)
    parser.add_argument("--aar", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(AuditSelfTests))
        return 0 if result.wasSuccessful() else 1
    root = Path(__file__).resolve().parents[1]
    aar = args.aar
    if aar is None:
        candidates = sorted((Path.home() / ".gradle/caches/modules-2/files-2.1/org.opencv/opencv" / OPENCV_VERSION).glob("*/*.aar"))
        aar = candidates[0] if candidates else None
    report = audit(root, args.apk or root / "scanner-sample/build/outputs/apk/debug/scanner-sample-debug.apk",
                   args.dependency_graph or root / "evidence/dependency-graph.txt", aar)
    print(json.dumps({"status": report["status"], "errors": report["errors"],
                      "license_review_status": report["license_review_status"],
                      "report": "evidence/release-audit.json"}, indent=2))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
