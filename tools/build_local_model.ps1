param(
    [Parameter(Mandatory=$true)][string]$Ndk,
    [Parameter(Mandatory=$true)][string]$LlamaSource,
    [Parameter(Mandatory=$true)][string]$BuildDir,
    [ValidateSet('arm64-v8a','armeabi-v7a','x86_64')][string]$Abi='arm64-v8a'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot
$toolchain = Join-Path $Ndk 'build/cmake/android.toolchain.cmake'
$source = Join-Path $project 'app/src/main/cpp'
$destination = Join-Path $project "app/src/main/jniLibs/$Abi"
cmake -S $source -B $BuildDir -G Ninja "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DLLAMA_SOURCE=$LlamaSource" "-DANDROID_ABI=$Abi" '-DANDROID_PLATFORM=android-26' '-DANDROID_STL=c++_static' '-DCMAKE_BUILD_TYPE=Release'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
cmake --build $BuildDir --target slovo_inference --parallel 3
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$binary=Join-Path $BuildDir 'libslovo_inference.so'
$strip=Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'
& $strip --strip-unneeded $binary
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Copy-Item -LiteralPath $binary -Destination (Join-Path $destination 'libslovo_inference.so') -Force

