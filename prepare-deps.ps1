$ErrorActionPreference = "Stop"
$ROOT = $PSScriptRoot
$DL = Join-Path $ROOT "dl"
$LLAMA = Join-Path $ROOT "llama"
$VENDOR = Join-Path $ROOT "vendor-libs"

New-Item -ItemType Directory -Path $DL -Force | Out-Null
New-Item -ItemType Directory -Path $LLAMA -Force | Out-Null
New-Item -ItemType Directory -Path $VENDOR -Force | Out-Null

$asset = "llama-b11980-bin-android-arm64-snapdragon.tar.gz"

if (-not (Test-Path (Join-Path $DL $asset))) {
    Write-Host "== downloading $asset =="
    gh release download b11980 --repo ggml-org/llama.cpp --pattern $asset --dir $DL --clobber
    if ($LASTEXITCODE -ne 0) { throw "download failed (need gh auth?)" }
}

if (-not (Test-Path (Join-Path $LLAMA "llama-b11980\bin\llama-server"))) {
    Write-Host "== extracting =="
    tar -xzf (Join-Path $DL $asset) -C $LLAMA
    if ($LASTEXITCODE -ne 0) { throw "extract failed" }
}

if (-not (Test-Path (Join-Path $VENDOR "libOpenCL.so"))) {
    Write-Host "== pulling vendor OpenCL loader from device =="
    adb pull /vendor/lib64/libOpenCL.so (Join-Path $VENDOR "libOpenCL.so")
    if ($LASTEXITCODE -ne 0) {
        throw "adb pull failed. Connect a Snapdragon device with USB debugging enabled."
    }
}

Write-Host "deps ready under $ROOT"
