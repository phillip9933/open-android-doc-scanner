"""Explicit, pinned OCR rebuild. Requires Android NDK 28.2.13676358 and CMake 3.22.1.
Downloads verified public source archives into build output, never runs upstream scripts.
"""
from pathlib import Path
import hashlib, json, os, shutil, subprocess, tarfile, urllib.request

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'scanner-ocr-tesseract/build/native-rebuild'
SDK = Path(os.environ['ANDROID_HOME'])
NDK = SDK / 'ndk/28.2.13676358'
CMAKE = SDK / 'cmake/3.22.1/bin/cmake'
NINJA = SDK / 'cmake/3.22.1/bin/ninja'
SOURCES = {
    'wrapper': ('https://github.com/adaptech-cz/Tesseract4Android/archive/refs/tags/4.9.0.tar.gz', '4569f9093d641d13188ba2dd36a74396661eaa3f9e44637ff1cac9a36076a20e', 'Tesseract4Android-4.9.0'),
    'tesseract': ('https://github.com/tesseract-ocr/tesseract/archive/refs/tags/5.5.3.tar.gz', '9218e62793116d42a9f6d14cd9348518b27f382096eea3d0f2d1a24616bb5884', 'tesseract-5.5.3'),
    'libpng': ('https://github.com/pnggroup/libpng/archive/refs/tags/v1.6.59.tar.gz', '2540302a1844ad2b2b501977abecfa850f265f97b78f065a712ab4074a89f5b5', 'libpng-1.6.59'),
}
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def replace(path, old, new):
    text = path.read_text()
    if text.count(old) != 1:
        raise RuntimeError(f'Unexpected source while patching {path.name}')
    path.write_text(text.replace(old, new))

def main():
    for path in (NDK / 'build/cmake/android.toolchain.cmake', CMAKE, NINJA):
        if not path.is_file(): raise RuntimeError(f'Missing toolchain: {path}')
    WORK.mkdir(parents=True, exist_ok=True)
    for name, (url, digest, directory) in SOURCES.items():
        archive = WORK / f'{name}.tar.gz'
        if not archive.exists():
            with urllib.request.urlopen(url, timeout=90) as response, archive.open('wb') as out:
                shutil.copyfileobj(response, out)
        if sha(archive) != digest: raise RuntimeError(f'Hash mismatch: {name}')
        extracted = WORK / directory
        if extracted.exists(): shutil.rmtree(extracted)
        with tarfile.open(archive) as tar: tar.extractall(WORK, filter='data')
    wrapper = WORK / SOURCES['wrapper'][2] / 'tesseract4android/src/main'
    cpp = wrapper / 'cpp'
    for name in ('tesseract', 'libpng'):
        shutil.rmtree(cpp / name / 'src')
        shutil.copytree(WORK / SOURCES[name][2], cpp / name / 'src')
    replace(cpp / 'CMakeLists.txt', 'set(CMAKE_CXX_STANDARD 17)', 'set(CMAKE_CXX_STANDARD 20)')
    # Explicit backports for the remaining model-loader findings in 5.5.3.
    replace(cpp / 'tesseract/src/src/lstm/plumbing.cpp',
            'if (size > 10000)', 'if (size == 0 || size > 10000)')
    replace(cpp / 'tesseract/src/src/classify/normmatch.cpp',
            '    stream >> unichar >> NumProtos;',
            '    stream.width(sizeof(unichar));\n    stream >> unichar >> NumProtos;')
    # Upstream Android glue expects a checked-in pnglibconf.h.
    shutil.copy2(cpp / 'libpng/src/scripts/pnglibconf.h.prebuilt', cpp / 'libpng/src/pnglibconf.h')
    java = ROOT / 'scanner-ocr-tesseract/src/main/java'
    shutil.copytree(wrapper / 'java', java, dirs_exist_ok=True)
    replace(java / 'com/googlecode/tesseract/android/TessBaseAPI.java',
            'import cz.adaptech.tesseract4android.BuildConfig;', '')
    replace(java / 'com/googlecode/tesseract/android/TessBaseAPI.java',
            'return BuildConfig.FLAVOR;', 'return "standard-hardened";')
    libraries = []
    for abi in ('arm64-v8a', 'x86_64'):
        build = WORK / ('build-' + abi)
        subprocess.run([str(CMAKE), '-S', str(cpp), '-B', str(build), '-G', 'Ninja',
            '-DCMAKE_MAKE_PROGRAM=' + str(NINJA), '-DCMAKE_TOOLCHAIN_FILE=' + str(NDK / 'build/cmake/android.toolchain.cmake'),
            '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static',
            '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now',
            '-DCMAKE_C_FLAGS=-ffile-prefix-map=' + str(WORK) + '=/ocr-source',
            '-DCMAKE_CXX_FLAGS=-ffile-prefix-map=' + str(WORK) + '=/ocr-source'], check=True)
        subprocess.run([str(CMAKE), '--build', str(build), '--parallel', '4'], check=True)
        target = ROOT / 'scanner-ocr-tesseract/src/main/jniLibs' / abi
        target.mkdir(parents=True, exist_ok=True)
        for name in ('jpeg', 'pngx', 'leptonica', 'tesseract'):
            source = next(build.rglob('lib' + name + '.so'))
            dest = target / source.name
            shutil.copy2(source, dest)
            subprocess.run([str(NDK / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'), '--strip-unneeded', str(dest)], check=True)
            libraries.append({'file':str(dest.relative_to(ROOT)), 'sha256':sha(dest)})
    notices = ROOT / 'scanner-ocr-tesseract/src/main/assets/third_party_licenses/ocr'
    for name, source in [('Tesseract-LICENSE.txt', cpp/'tesseract/src/LICENSE'), ('libpng-LICENSE.txt', cpp/'libpng/src/LICENSE')]:
        shutil.copy2(source, notices/name)
    provenance = json.loads((notices/'provenance.json').read_text())
    for item in provenance:
        if item['file'] == 'Tesseract-LICENSE.txt': item['url'] = 'https://raw.githubusercontent.com/tesseract-ocr/tesseract/5.5.3/LICENSE'
        if item['file'] == 'libpng-LICENSE.txt': item['url'] = 'https://raw.githubusercontent.com/pnggroup/libpng/v1.6.59/LICENSE'
        item['sha256'] = sha(notices/item['file'])
    (notices/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    report = {'sources':{k:{'url':v[0],'sha256':v[1]} for k,v in SOURCES.items()},
        'patches':['Reject empty Plumbing stack (GHSA-f6h7-cqr4-6fx4)', 'Bound ReadNormProtos extraction (GHSA-5j2p-r5vc-q7f3)'],
        'ndk':'28.2.13676358','cmake':'3.22.1','cxx_standard':20,'android_api':26,'libraries':libraries}
    report['java_sources'] = [{'file':str(p.relative_to(ROOT)), 'sha256':sha(p)} for p in sorted(java.rglob('*.java'))]
    (ROOT/'evidence/ocr-native-build.json').write_text(json.dumps(report,indent=2)+'\n')
if __name__ == '__main__': main()
