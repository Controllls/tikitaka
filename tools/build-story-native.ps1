param(
    [Parameter(Mandatory=$true)][string]$Ndk,
    [Parameter(Mandatory=$true)][string]$CMake,
    [Parameter(Mandatory=$true)][string]$Ninja,
    [Parameter(Mandatory=$true)][string]$Cache,
    [int]$Jobs = 4
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$cacheRoot = [IO.Path]::GetFullPath($Cache)
New-Item -ItemType Directory -Force -Path $cacheRoot | Out-Null
$archive = Join-Path $cacheRoot 'llama-b12360.zip'
$expected = '8689161172e3aa2a51189b6a86a8c1107d6bed5c036d4029b8c43b49ace4144e'
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/ggml-org/llama.cpp/archive/refs/tags/b12360.zip' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'llama.cpp archive checksum mismatch' }
$source = Join-Path $cacheRoot 'llama.cpp-b12360'
if (-not (Test-Path -LiteralPath $source)) { Expand-Archive -LiteralPath $archive -DestinationPath $cacheRoot }
$toolchain = Join-Path $Ndk 'build/cmake/android.toolchain.cmake'
$strip = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'
foreach ($abi in @('arm64-v8a','x86_64')) {
    $build = Join-Path $cacheRoot "build-$abi"
    & $CMake -S "$project/app/src/main/cpp/story" -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$Ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release "-DLLAMA_SOURCE=$source"
    if ($LASTEXITCODE -ne 0) { throw "Configure failed: $abi" }
    & $CMake --build $build --target tikitaka-story -j $Jobs
    if ($LASTEXITCODE -ne 0) { throw "Build failed: $abi" }
    $destination = Join-Path $project "app/src/main/jniLibs/$abi/libtikitaka-story.so"
    Copy-Item -LiteralPath (Join-Path $build 'libtikitaka-story.so') -Destination $destination
    & $strip --strip-unneeded $destination
    if ($LASTEXITCODE -ne 0) { throw "Strip failed: $abi" }
    Get-FileHash -LiteralPath $destination -Algorithm SHA256
}
