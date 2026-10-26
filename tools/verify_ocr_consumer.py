"""Build independent hosts from the SDK archive, never from Gradle project dependencies.

Uses ignored build/ocr-consumer, the repository's Gradle wrapper and installed SDK.
Does not publish remotely or install an app. Run local Maven publication and
package_maven_release.py first. Gradle may resolve dependencies from Google/Maven Central.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
version = re.search(r'allprojects\s*\{[^}]*version\s*=\s*"([^"]+)"', (root/'build.gradle.kts').read_text()).group(1)
archive = root/'release'/f'open-android-doc-scanner-{version}-sdk-maven.zip'
work = root/'build/ocr-consumer'
work.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(archive) as z:
    for name in z.namelist():
        if not (work/name).resolve().is_relative_to(work.resolve()):
            raise ValueError('Unsafe archive member')
    z.extractall(work)
(work/'settings.gradle.kts').write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { maven { url=uri("maven") }; google(); mavenCentral() } }
rootProject.name="independent-scanner-consumers"
include(":plain", ":ocr")
''')
(work/'build.gradle.kts').write_text('''plugins {
 id("com.android.application") version "8.13.2" apply false
 kotlin("android") version "2.2.20" apply false
}
''')
(work/'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n')
if (root/'local.properties').exists():
    (work/'local.properties').write_bytes((root/'local.properties').read_bytes())
for flavor in ('plain','ocr'):
    module=work/flavor
    source=module/'src/main/java/example'
    source.mkdir(parents=True,exist_ok=True)
    (module/'src/main/AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="Scanner consumer" /></manifest>')
    deps='implementation("dev.offlinescan:scanner-ui-compose:'+version+'"); implementation("dev.offlinescan:scanner-processing-opencv:'+version+'")'
    if flavor=='ocr': deps+='; implementation("dev.offlinescan:scanner-ocr-tesseract:'+version+'")'
    (module/'build.gradle.kts').write_text('''plugins { id("com.android.application"); kotlin("android") }
android { namespace="example.'''+flavor+'''"; compileSdk=36
 defaultConfig { applicationId="example.scanner.'''+flavor+'''"; minSdk=26; targetSdk=36 }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
}
dependencies { '''+deps+' }\n')
    code='package example\nimport dev.offlinescan.core.*\nimport dev.offlinescan.processing.OpenCvProcessor\nclass Consumer { val processor = OpenCvProcessor(); val config = ScanConfig() }\n'
    if flavor=='ocr':
        code+='''fun recognize(file: java.io.File, models: java.io.File): OcrPage =
 dev.offlinescan.ocr.TesseractTextRecognizer(models).recognize(file, "front",
 OcrConfig(languages=listOf("eng", "jpn"), layout=OcrLayout.SPARSE), NeverCancelled)
fun fields(file: java.io.File, models: java.io.File): List<OcrFieldResult> =
 dev.offlinescan.ocr.TesseractTextRecognizer(models).recognizeFields(file, "front", listOf(
 OcrFieldRequest("number", OcrConfig(languages=listOf("eng"), layout=OcrLayout.SINGLE_LINE,
 region=OcrRegion(0.1,0.1,0.8,0.3),characterWhitelist="0123456789",straightenLine=true))))
'''
    (source/'Consumer.kt').write_text(code)
subprocess.run([str(root/'gradlew'),'-p',str(work),':plain:assembleDebug',':ocr:assembleDebug','--console=plain'],check=True)
report={'sdk_version':version,'archive_sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'consumers':{}}
for flavor in ('plain','ocr'):
    apk=work/flavor/f'build/outputs/apk/debug/{flavor}-debug.apk'
    with zipfile.ZipFile(apk) as z:
        names=z.namelist()
        native=[n for n in names if n.startswith('lib/') and n.endswith('.so')]
        ocr_libs=[n for n in native if n.endswith('/libtesseract.so')]
        assert bool(ocr_libs)==(flavor=='ocr'), (flavor,ocr_libs)
        assert not any(n.endswith('.traineddata') for n in names), 'Models must remain host-owned'
        report['consumers'][flavor]={'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'native_libraries':native,'models_bundled':False,'ocr_bundled':bool(ocr_libs)}
(root/'evidence/ocr-consumer-validation.json').write_text(json.dumps(report,indent=2)+'\n')
print('Independent plain/OCR hosts compiled from archived Maven artifacts; optional native/model ownership checks passed.')
