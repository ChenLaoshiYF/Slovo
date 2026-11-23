param(
    [Parameter(Mandatory=$true)][string]$Ndk,
    [Parameter(Mandatory=$true)][string]$EspeakSource,
    [Parameter(Mandatory=$true)][string]$BuildDir,
    [ValidateSet('arm64-v8a','armeabi-v7a','x86_64')][string]$Abi='arm64-v8a'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot
$toolchain = Join-Path $Ndk 'build/cmake/android.toolchain.cmake'
cmake -S "$project/app/src/main/cpp/pronunciation" -B $BuildDir -G Ninja "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DESPEAK_SOURCE=$EspeakSource" "-DANDROID_ABI=$Abi" '-DANDROID_PLATFORM=android-26' '-DANDROID_STL=c++_static' '-DCMAKE_BUILD_TYPE=Release'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
cmake --build $BuildDir --target slovo_pronunciation --parallel 3
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$binary=Join-Path $BuildDir 'libslovo_pronunciation.so'
& "$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" --strip-unneeded $binary
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Copy-Item -LiteralPath $binary -Destination "$project/app/src/main/jniLibs/$Abi/libslovo_pronunciation.so" -Force
