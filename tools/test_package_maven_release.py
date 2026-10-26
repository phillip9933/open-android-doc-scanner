"""Regression tests for release and timestamped snapshot archive selection."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

MODULES=('scanner-camera','scanner-core','scanner-export','scanner-processing-opencv','scanner-ui-compose','scanner-ocr-tesseract')
class PackagingTest(unittest.TestCase):
    def package(self, snapshot, missing=False):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'tools').mkdir();(root/'docs').mkdir();(root/'third-party').mkdir();(root/'evidence').mkdir()
            shutil.copy(Path(__file__).with_name('package_maven_release.py'),root/'tools/package_maven_release.py')
            version='0.8.0-SNAPSHOT' if snapshot else '0.8.0'
            (root/'build.gradle.kts').write_text(f'allprojects {{ version = "{version}" }}')
            for path in ('LICENSE','NOTICE','THIRD-PARTY-NOTICES.md','docs/RELEASE-0.8.0.md','docs/NATIVE-BUILD.md','docs/OCR.md','docs/OCR-SECURITY.md','evidence/ocr-native-build.json'):(root/path).write_text('fixture')
            for module in MODULES:
                target=root/'release/maven/dev/offlinescan'/module/version;target.mkdir(parents=True)
                suffix='jar' if module=='scanner-core' else 'aar'
                value='0.8.0-20261026.010101-2' if snapshot else version
                entries=[]
                for ext in (suffix,'pom','module'):
                    (target/f'{module}-{value}.{ext}').write_text('current')
                    entries.append(f'<snapshotVersion><extension>{ext}</extension><value>{value}</value></snapshotVersion>')
                if snapshot:
                    (target/'maven-metadata.xml').write_text('<metadata><versioning><snapshotVersions>'+''.join(entries)+'</snapshotVersions></versioning></metadata>')
                    (target/f'{module}-0.8.0-20261025.010101-1.{suffix}').write_text('stale')
                if missing and module=='scanner-core':(target/f'{module}-{value}.jar').unlink()
            run=subprocess.run([sys.executable,str(root/'tools/package_maven_release.py')],capture_output=True,text=True)
            if missing:
                self.assertNotEqual(0,run.returncode);return
            self.assertEqual(0,run.returncode,run.stderr)
            archive=root/'release'/f'open-android-doc-scanner-{version}-sdk-maven.zip'
            with zipfile.ZipFile(archive) as z:
                self.assertFalse(any('20261025' in n for n in z.namelist()))
                self.assertIn('docs/OCR.md',z.namelist())
                self.assertEqual(6,sum(n.endswith('.module') for n in z.namelist()))
            before=archive.read_bytes()
            subprocess.run([sys.executable,str(root/'tools/package_maven_release.py')],check=True,capture_output=True)
            self.assertEqual(before,archive.read_bytes())
    def test_stable(self):self.package(False)
    def test_snapshot_selects_current_publication(self):self.package(True)
    def test_missing_snapshot_fails(self):self.package(True,True)
if __name__=='__main__':unittest.main()
