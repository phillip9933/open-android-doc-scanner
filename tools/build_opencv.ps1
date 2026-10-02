# SPDX-License-Identifier: Apache-2.0
# Native-only OpenCV build. This script performs no downloads or package installation.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $SourceArchive,
    [Parameter(Mandatory = $true)] [string] $AndroidSdk,
    [Parameter(Mandatory = $true)] [string] $Python,
    [string] $BuildRoot,
    [string] $OutputDirectory,
    [ValidateRange(1, 64)] [int] $Jobs = 4
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if (-not $BuildRoot) { $BuildRoot = Join-Path $projectRoot 'build/native-opencv-4.12.0' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot 'scanner-processing-opencv/src/main/jniLibs' }
$buildPath = [IO.Path]::GetFullPath($BuildRoot)
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
$archivePath = (Resolve-Path -LiteralPath $SourceArchive).Path
$sdkPath = (Resolve-Path -LiteralPath $AndroidSdk).Path
$pythonPath = (Resolve-Path -LiteralPath $Python).Path
$sourceSha256 = 'fa3faf7581f1fa943c9e670cf57dd6ba1c5b4178f363a188a2c8bff1eb28b7e4'
$ndkVersion = '28.2.13676358'
$cmakeVersion = '3.22.1'
$ndkPath = Join-Path $sdkPath "ndk/$ndkVersion"
$cmakeExe = Join-Path $sdkPath "cmake/$cmakeVersion/bin/cmake.exe"
$ninjaExe = Join-Path $sdkPath "cmake/$cmakeVersion/bin/ninja.exe"
$llvmDirectory = Join-Path $ndkPath 'toolchains/llvm/prebuilt/windows-x86_64'
$readelfExe = Join-Path $llvmDirectory 'bin/llvm-readelf.exe'
$nmExe = Join-Path $llvmDirectory 'bin/llvm-nm.exe'
$stringsExe = Join-Path $llvmDirectory 'bin/llvm-strings.exe'
$toolchainFile = Join-Path $ndkPath 'build/cmake/android.toolchain.cmake'
foreach ($required in @($cmakeExe, $ninjaExe, $readelfExe, $nmExe, $stringsExe, $toolchainFile, (Join-Path $ndkPath 'NOTICE'), (Join-Path $ndkPath 'NOTICE.toolchain'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required pinned build-tool file missing: $required" }
}
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sourceSha256) {
    throw 'Source archive SHA-256 does not match the pinned OpenCV 4.12.0 GitHub tag archive.'
}
$ndkProperties = Get-Content -LiteralPath (Join-Path $ndkPath 'source.properties') -Raw
if ($ndkProperties -notmatch ('(?m)^Pkg\.Revision\s*=\s*' + [regex]::Escape($ndkVersion) + '\s*$')) { throw 'NDK revision mismatch' }
$cmakeIdentity = (& $cmakeExe --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $cmakeIdentity -notmatch '^cmake version 3\.22\.1\b') { throw 'CMake revision mismatch' }
$pythonIdentity = (& $pythonPath --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $pythonIdentity -notmatch '^Python 3\.') { throw 'Python 3 interpreter check failed' }
$ninjaIdentity = (& $ninjaExe --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Ninja execution failed' }
New-Item -ItemType Directory -Force -Path $buildPath | Out-Null
$sourceBase = Join-Path $buildPath 'verified-source'
New-Item -ItemType Directory -Force -Path $sourceBase | Out-Null

# Verify every extracted file against the immutable ZIP. Refuse modified or extra source files.
# No directory is recursively deleted, and every ZIP entry is checked for path traversal.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($archivePath)
$sha = [Security.Cryptography.SHA256]::Create()
$expectedFiles = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$sourcePrefix = [IO.Path]::GetFullPath($sourceBase).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
try {
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName.EndsWith('/')) { continue }
        $target = [IO.Path]::GetFullPath((Join-Path $sourceBase $entry.FullName))
        if (-not $target.StartsWith($sourcePrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe archive entry: $($entry.FullName)" }
        if (-not $expectedFiles.Add($target)) { throw "Duplicate archive entry: $($entry.FullName)" }
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
            New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($target)) | Out-Null
            $inputStream = $entry.Open()
            $outputStream = [IO.File]::Open($target, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
            try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose(); $inputStream.Dispose() }
        }
        if ((Get-Item -LiteralPath $target).Length -ne $entry.Length) { throw "Source file length mismatch: $target" }
        $entryStream = $entry.Open()
        try { $expectedHash = [BitConverter]::ToString($sha.ComputeHash($entryStream)).Replace('-', '').ToLowerInvariant() } finally { $entryStream.Dispose() }
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) { throw "Modified source file: $target" }
    }
    foreach ($existing in [IO.Directory]::EnumerateFiles($sourceBase, '*', [IO.SearchOption]::AllDirectories)) {
        if (-not $expectedFiles.Contains([IO.Path]::GetFullPath($existing))) { throw "Unexpected file in verified source tree: $existing" }
    }
} finally { $sha.Dispose(); $zip.Dispose() }
$sourcePath = Join-Path $sourceBase 'opencv-4.12.0'
$hookDirectory = Join-Path $buildPath 'offline-cmake-hooks'
New-Item -ItemType Directory -Force -Path $hookDirectory | Out-Null
'message(FATAL_ERROR "This source-only baseline forbids dependency downloads; an optional component was unexpectedly enabled.")' | Set-Content -LiteralPath (Join-Path $hookDirectory 'OPENCV_DOWNLOAD_PRE.cmake') -Encoding UTF8
$versionHeader = Get-Content -LiteralPath (Join-Path $sourcePath 'modules/core/include/opencv2/core/version.hpp') -Raw
foreach ($versionPair in @(@('MAJOR', '4'), @('MINOR', '12'), @('REVISION', '0'))) {
    if ($versionHeader -notmatch ('#define\s+CV_VERSION_' + $versionPair[0] + '\s+' + $versionPair[1] + '\b')) { throw 'Source version header mismatch' }
}

function Invoke-LoggedNative {
    param([string] $Executable, [string[]] $Arguments, [string] $LogPath)
    $previousBytecodeSetting = [Environment]::GetEnvironmentVariable('PYTHONDONTWRITEBYTECODE', 'Process')
    try {
        # JNI generators import source-tree Python files. Keep the verified tree immutable on reruns.
        [Environment]::SetEnvironmentVariable('PYTHONDONTWRITEBYTECODE', '1', 'Process')
        & $Executable @Arguments 2>&1 | Tee-Object -FilePath $LogPath | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Native command failed ($LASTEXITCODE). See $LogPath" }
    } finally { [Environment]::SetEnvironmentVariable('PYTHONDONTWRITEBYTECODE', $previousBytecodeSetting, 'Process') }
}
function Assert-CacheValue {
    param([string] $Cache, [string] $Name, [string] $Expected)
    $pattern = '(?m)^' + [regex]::Escape($Name) + ':[^=]+=' + [regex]::Escape($Expected) + '\r?$'
    if ($Cache -notmatch $pattern) { throw "CMake did not retain required setting $Name=$Expected" }
}
function Inspect-AndroidLibrary {
    param([string] $Library, [string] $Abi, [string] $AuditDirectory, [switch] $OpenCv)
    $baseName = [IO.Path]::GetFileName($Library)
    $elf = (& $readelfExe -h -l -d --wide $Library 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "ELF inspection failed: $Library" }
    $elf | Set-Content -LiteralPath (Join-Path $AuditDirectory "$baseName.readelf.txt") -Encoding UTF8
    $machine = if ($Abi -eq 'arm64-v8a') { 'AArch64' } else { 'Advanced Micro Devices X86-64' }
    if ($elf -notmatch 'Class:\s+ELF64' -or $elf -notmatch ('Machine:\s+' + [regex]::Escape($machine))) { throw "ELF architecture mismatch for $Abi : $Library" }
    $loadLines = @($elf -split '\r?\n' | Where-Object { $_ -match '^\s*LOAD\s' })
    if ($loadLines.Count -eq 0) { throw "Missing ELF load segments: $Library" }
    foreach ($line in $loadLines) {
        if ($line -notmatch '(0x[0-9a-fA-F]+)\s*$' -or [Convert]::ToInt64($Matches[1].Substring(2), 16) -lt 16384) { throw "Library lacks 16 KB load-segment alignment: $Library" }
    }
    $needed = @([regex]::Matches($elf, '\(NEEDED\).*?\[([^\]]+)\]') | ForEach-Object { $_.Groups[1].Value })
    $allowed = @('libc++_shared.so', 'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libjnigraphics.so', 'libz.so', 'libandroid.so')
    foreach ($dependency in $needed) { if ($dependency -notin $allowed) { throw "Unexpected ELF dependency $dependency in $Library" } }
    if ($OpenCv) {
        $symbols = (& $nmExe --dynamic --defined-only $Library 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0) { throw 'JNI symbol inspection failed' }
        $symbols | Set-Content -LiteralPath (Join-Path $AuditDirectory "$baseName.symbols.txt") -Encoding UTF8
        foreach ($symbolPrefix in @('Java_org_opencv_core_Core_getBuildInformation', 'Java_org_opencv_core_Mat_n_1Mat', 'Java_org_opencv_imgproc_Imgproc_Canny', 'Java_org_opencv_android_Utils_nBitmapToMat2', 'Java_org_opencv_android_Utils_nMatToBitmap2')) {
            if ($symbols -notmatch [regex]::Escape($symbolPrefix)) { throw "Required JNI export missing: $symbolPrefix" }
        }
        $strings = (& $stringsExe $Library 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0) { throw 'Binary string inspection failed' }
        if ($strings -match '(?i)[A-Z]:[/\\]Users[/\\]') { throw 'Personal build-machine path remains in native binary' }
        if ($strings -match '(?i)ippicv|ippiw|ipphal|__itt_|tbb::|ade::|kleidicv') { throw 'Forbidden third-party native component marker in OpenCV binary' }
    }
    return [ordered]@{ file = $baseName; bytes = (Get-Item -LiteralPath $Library).Length; sha256 = (Get-FileHash -LiteralPath $Library -Algorithm SHA256).Hash.ToLowerInvariant(); needed = $needed; loadSegmentAlignmentMinimum = 16384 }
}

# Static core/imgproc archives are incorporated into one shared JNI library; no world module.
# Explicit codec/backend switches stop OpenCV's global dependency discovery from bundling extras.
$disabled = @(
    'WITH_IPP', 'WITH_ITT', 'WITH_TBB', 'WITH_ADE', 'WITH_OPENCL', 'WITH_OPENCL_SVM', 'WITH_OPENCLAMDFFT', 'WITH_OPENCLAMDBLAS',
    'WITH_KLEIDICV', 'WITH_CAROTENE', 'WITH_CPUFEATURES', 'WITH_FASTCV', 'WITH_EIGEN', 'WITH_LAPACK', 'WITH_OPENMP',
    'WITH_CUDA', 'WITH_CUDNN', 'WITH_OPENVINO', 'WITH_WEBNN', 'WITH_VULKAN', 'WITH_HALIDE', 'WITH_OPENVX',
    'WITH_PROTOBUF', 'WITH_FLATBUFFERS', 'WITH_QUIRC', 'WITH_JPEG', 'WITH_PNG', 'WITH_TIFF', 'WITH_WEBP', 'WITH_OPENJPEG', 'WITH_JASPER', 'WITH_OPENEXR', 'WITH_AVIF', 'WITH_JPEGXL', 'WITH_SPNG',
    'WITH_FFMPEG', 'WITH_GSTREAMER', 'WITH_V4L', 'WITH_ANDROID_MEDIANDK', 'WITH_ANDROID_NATIVE_CAMERA', 'WITH_OPENGL',
    'BUILD_IPP_IW', 'BUILD_ITT', 'BUILD_TBB', 'BUILD_ZLIB', 'BUILD_JPEG', 'BUILD_PNG', 'BUILD_TIFF', 'BUILD_WEBP', 'BUILD_OPENJPEG', 'BUILD_JASPER', 'BUILD_OPENEXR', 'BUILD_PROTOBUF',
    'BUILD_SHARED_LIBS', 'BUILD_opencv_world', 'BUILD_opencv_gapi', 'BUILD_opencv_dnn', 'BUILD_opencv_imgcodecs', 'BUILD_opencv_highgui', 'BUILD_opencv_videoio',
    'BUILD_TESTS', 'BUILD_PERF_TESTS', 'BUILD_EXAMPLES', 'BUILD_ANDROID_EXAMPLES', 'INSTALL_ANDROID_EXAMPLES', 'BUILD_DOCS', 'BUILD_ANDROID_PROJECTS', 'BUILD_KOTLIN_EXTENSIONS',
    'BUILD_opencv_python2', 'BUILD_opencv_python3', 'OPENCV_ENABLE_NONFREE', 'OPENCV_TRACE', 'CV_TRACE', 'ENABLE_CCACHE'
)
$commonArguments = @('-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_LIST=core,imgproc,java', '-DBUILD_JAVA=ON', '-DBUILD_FAT_JAVA_LIB=ON', '-DWITH_PTHREADS_PF=ON',
    '-DANDROID_PLATFORM=android-26', '-DANDROID_NATIVE_API_LEVEL=26', '-DANDROID_STL=c++_shared', '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON',
    '-DANDROID_PROJECTS_BUILD_TYPE=GRADLE', '-DANDROID_COMPILE_SDK_VERSION=35', '-DBUILD_INFO_SKIP_TIMESTAMP=ON', '-DOPENCV_VCSVERSION=4.12.0',
    '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384', '-DCMAKE_MODULE_LINKER_FLAGS=-Wl,-z,max-page-size=16384',
    "-DCMAKE_TOOLCHAIN_FILE=$($toolchainFile.Replace('\', '/'))", "-DCMAKE_MAKE_PROGRAM=$($ninjaExe.Replace('\', '/'))", "-DANDROID_SDK=$($sdkPath.Replace('\', '/'))",
    "-DOPENCV_DOWNLOAD_PATH=$((Join-Path $buildPath 'unused-download-cache').Replace('\', '/'))", "-DOPENCV_CMAKE_HOOKS_DIR=$($hookDirectory.Replace('\', '/'))",
    "-DPYTHON_DEFAULT_EXECUTABLE=$($pythonPath.Replace('\', '/'))", "-DPYTHON3_EXECUTABLE=$($pythonPath.Replace('\', '/'))")
# Keep __FILE__/debug paths independent of the builder's machine.
$prefixMaps = "-ffile-prefix-map=$($buildPath.Replace('\', '/'))=/opencv-build -ffile-prefix-map=$($sdkPath.Replace('\', '/'))=/android-sdk -ffile-prefix-map=$($sourcePath.Replace('\', '/'))=/opencv-source"
$commonArguments += @("-DCMAKE_C_FLAGS=$prefixMaps", "-DCMAKE_CXX_FLAGS=$prefixMaps")
$commonArguments += @($disabled | ForEach-Object { "-D$_=OFF" })
$records = @()
$staged = @()
foreach ($abi in @('arm64-v8a', 'x86_64')) {
    $abiBuild = Join-Path $buildPath $abi
    $auditDirectory = Join-Path $abiBuild 'audit'
    New-Item -ItemType Directory -Force -Path $auditDirectory | Out-Null
    $arguments = @('-S', $sourcePath, '-B', $abiBuild, "-DANDROID_ABI=$abi", "-DANDROID_NDK_ABI_NAME=$abi") + $commonArguments
    $arguments | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $auditDirectory 'configure-arguments.json') -Encoding UTF8
    Invoke-LoggedNative $cmakeExe $arguments (Join-Path $auditDirectory 'configure.log')
    $cache = Get-Content -LiteralPath (Join-Path $abiBuild 'CMakeCache.txt') -Raw
    foreach ($name in @('WITH_IPP', 'WITH_ITT', 'WITH_TBB', 'WITH_ADE', 'WITH_OPENCL', 'WITH_KLEIDICV', 'WITH_CAROTENE', 'WITH_CPUFEATURES', 'WITH_FLATBUFFERS', 'BUILD_SHARED_LIBS', 'OPENCV_ENABLE_NONFREE', 'CV_TRACE')) { Assert-CacheValue $cache $name 'OFF' }
    Assert-CacheValue $cache 'BUILD_LIST' 'core,imgproc,java'
    Assert-CacheValue $cache 'BUILD_FAT_JAVA_LIB' 'ON'
    $moduleMatch = [regex]::Match($cache, '(?m)^OPENCV_MODULES_BUILD:INTERNAL=([^\r\n]+)')
    if (-not $moduleMatch.Success) { throw 'OpenCV module inventory is absent from CMake cache' }
    $modules = @($moduleMatch.Groups[1].Value -split ';')
    $expectedModules = @('opencv_core', 'opencv_imgproc', 'opencv_java', 'opencv_java_bindings_generator')
    foreach ($module in $modules) { if ($module -notin $expectedModules) { throw "Unexpected compiled module: $module" } }
    foreach ($module in $expectedModules) { if ($module -notin $modules) { throw "Required module absent: $module" } }
    $configuration = Get-Content -LiteralPath (Join-Path $abiBuild 'cvconfig.h') -Raw
    if ($configuration -match '(?m)^\s*#define\s+(HAVE_IPP\w*|HAVE_ITT|HAVE_TBB|HAVE_OPENCL\w*|HAVE_KLEIDICV|HAVE_CAROTENE|HAVE_CPUFEATURES)\b') { throw 'Forbidden native feature enabled in cvconfig.h' }
    # OpenCV embeds a generated diagnostic string containing tool paths and compiler flags.
    # Redact that GENERATED include, not the verified upstream source tree or binary bytes.
    foreach ($generatedName in @('modules/core/version_string.inc', 'opencv_data_config.hpp')) {
        $generatedInclude = Join-Path $abiBuild $generatedName
        $diagnostic = [IO.File]::ReadAllText($generatedInclude)
        foreach ($mapping in @(@($sourcePath, '/opencv-source'), @($buildPath, '/opencv-build'), @($sdkPath, '/android-sdk'), @($pythonPath, '/build-tools/python'))) {
            $diagnostic = $diagnostic.Replace($mapping[0].Replace('\', '/'), $mapping[1]).Replace($mapping[0].Replace('\', '\\'), $mapping[1])
        }
        [IO.File]::WriteAllText($generatedInclude, $diagnostic, [Text.UTF8Encoding]::new($false))
    }
    Invoke-LoggedNative $cmakeExe @('--build', $abiBuild, '--target', 'opencv_java', '--parallel', $Jobs.ToString()) (Join-Path $auditDirectory 'build.log')
    $staticArchives = @(Get-ChildItem -LiteralPath $abiBuild -Recurse -File -Filter '*.a')
    if ($staticArchives.Count -ne 2 -or @($staticArchives | Where-Object { $_.Name -notin @('libopencv_core.a', 'libopencv_imgproc.a') }).Count -ne 0) {
        throw 'Unexpected static native archive inventory: baseline must contain only core and imgproc archives'
    }
    $nativeLibrary = Join-Path $abiBuild "jni/$abi/libopencv_java4.so"
    if (-not (Test-Path -LiteralPath $nativeLibrary -PathType Leaf)) { throw "Expected JNI output missing: $nativeLibrary" }
    $triple = if ($abi -eq 'arm64-v8a') { 'aarch64-linux-android' } else { 'x86_64-linux-android' }
    $runtimeLibrary = Join-Path $llvmDirectory "sysroot/usr/lib/$triple/libc++_shared.so"
    if (-not (Test-Path -LiteralPath $runtimeLibrary -PathType Leaf)) { throw "Matching NDK C++ runtime missing: $runtimeLibrary" }
    $nativeRecord = Inspect-AndroidLibrary $nativeLibrary $abi $auditDirectory -OpenCv
    $runtimeRecord = Inspect-AndroidLibrary $runtimeLibrary $abi $auditDirectory
    $records += [ordered]@{ abi = $abi; modules = $modules; opencv = $nativeRecord; runtime = $runtimeRecord }
    $staged += [ordered]@{ abi = $abi; native = $nativeLibrary; runtime = $runtimeLibrary }
}
# Publish only after BOTH ABIs have built and passed source/configuration/ELF/JNI checks.
foreach ($item in $staged) {
    $abiOutput = Join-Path $outputPath $item.abi
    New-Item -ItemType Directory -Force -Path $abiOutput | Out-Null
    Copy-Item -LiteralPath $item.native -Destination (Join-Path $abiOutput 'libopencv_java4.so') -Force
    Copy-Item -LiteralPath $item.runtime -Destination (Join-Path $abiOutput 'libc++_shared.so') -Force
}
$licenses = Join-Path $buildPath 'audit/licenses'
New-Item -ItemType Directory -Force -Path $licenses | Out-Null
Copy-Item -LiteralPath (Join-Path $sourcePath 'LICENSE') -Destination (Join-Path $licenses 'OpenCV-Apache-2.0.txt') -Force
Copy-Item -LiteralPath (Join-Path $ndkPath 'NOTICE') -Destination (Join-Path $licenses 'Android-NDK-NOTICE.txt') -Force
Copy-Item -LiteralPath (Join-Path $ndkPath 'NOTICE.toolchain') -Destination (Join-Path $licenses 'Android-NDK-toolchain-NOTICE.txt') -Force
$softfloat = Get-Content -LiteralPath (Join-Path $sourcePath 'modules/core/src/softfloat.cpp') -Raw
$softfloat.Substring(0, $softfloat.IndexOf('#include')) | Set-Content -LiteralPath (Join-Path $licenses 'OpenCV-SoftFloat-fdlibm-notices.txt') -Encoding UTF8
$report = [ordered]@{ schemaVersion = 1; openCvVersion = '4.12.0'; sourceArchiveSha256 = $sourceSha256; sourceUrl = 'https://github.com/opencv/opencv/archive/refs/tags/4.12.0.zip'; ndkVersion = $ndkVersion; cmake = $cmakeIdentity; ninja = $ninjaIdentity; python = $pythonIdentity; androidApi = 26; outputDirectory = $outputPath; pathPrivacy = "Compiler file-prefix maps plus anonymized generated OpenCV build diagnostics and unused sample-data lookup paths; verified upstream source unchanged"; artifacts = $records }
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $buildPath 'audit/native-build-manifest.json') -Encoding UTF8
Write-Host "Source-built libraries published to $outputPath"
Write-Host "Build audit and hashes: $(Join-Path $buildPath 'audit/native-build-manifest.json')"
