"""Package the audited local candidate; never publishes to an external repository."""
from pathlib import Path
import hashlib
import json
import shutil
import re
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
version = re.search(r'allprojects\s*\{[^}]*version\s*=\s*"([^"]+)"',
                    (root / 'build.gradle.kts').read_text(), re.S).group(1)
apk = root / 'scanner-sample/build/outputs/apk/debug/scanner-sample-debug.apk'
report = json.loads((root / 'evidence/release-audit.json').read_text())
assert report['status'] == 'passed', 'Audit the final APK before packaging'
assert hashlib.sha256(apk.read_bytes()).hexdigest() == report['apk']['sha256'], 'APK changed after audit'
for required in ['README.md', 'LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES.md', 'docs/QUALITY-REPORT.md']:
    assert (root / required).is_file(), f'Missing release input: {required}'
release = root / 'release'
release.mkdir(exist_ok=True)
shutil.copy2(apk, release / f'scanner-sample-{version}.apk')
source_zip = release / f'offline-scanner-{version}-source.zip'
with zipfile.ZipFile(source_zip, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    # Package only version-controlled inputs, never unrelated local files or secrets.
    tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
    for name in sorted(filter(None, tracked)):
        file = root / name
        if file.is_file():
            archive.write(file, Path('offline-scanner') / name)
with zipfile.ZipFile(source_zip) as archive:
    assert archive.testzip() is None, 'Source archive integrity failure'
entries = []
for file in sorted([release / f'scanner-sample-{version}.apk', source_zip, release / f'open-android-doc-scanner-{version}-sdk-maven.zip']):
    if file.is_file() and file.name != 'SHA256SUMS':
        entries.append(hashlib.sha256(file.read_bytes()).hexdigest() + '  ' + file.relative_to(release).as_posix())
(release / 'SHA256SUMS').write_text('\n'.join(entries) + '\n', encoding='utf-8')
print(f'Packaged audited APK, source ZIP, and {len(entries)} checksum entries in release/.')
