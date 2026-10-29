param(
    [string]$VersionName = "1.0",
    [int]$VersionCode = 1
)

$ErrorActionPreference = "Stop"

$ROOT   = $PSScriptRoot
$APP    = Join-Path $ROOT "app"
$OUT    = Join-Path $ROOT "out"
$LLAMA  = Join-Path $ROOT "llama\llama-b11980"
$VENDOR = Join-Path $ROOT "vendor-libs"
$KS     = Join-Path $ROOT "keystore\debug.keystore"

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$SDK = $env:ANDROID_HOME
if (-not (Test-Path $SDK)) { throw "Android SDK not found. Set ANDROID_HOME." }

$BT = Get-ChildItem (Join-Path $SDK "build-tools") -Directory |
      Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $BT) { throw "No build-tools found." }
$BT = $BT.FullName

$PLATFORM = Join-Path $SDK "platforms\android-35"
if (-not (Test-Path $PLATFORM)) {
    $PLATFORM = (Get-ChildItem (Join-Path $SDK "platforms") -Directory |
                 Sort-Object Name -Descending | Select-Object -First 1).FullName
}
$AJAR = Join-Path $PLATFORM "android.jar"

$PY = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $PY) { throw "python not found on PATH (needed by assemble.py)." }

if (-not (Test-Path (Join-Path $LLAMA "bin\llama-server"))) {
    throw "Missing llama.cpp prebuilt tree. Run .\prepare-deps.ps1 first."
}
if (-not (Test-Path (Join-Path $VENDOR "libOpenCL.so"))) {
    throw "Missing vendor-libs/libOpenCL.so. Run .\prepare-deps.ps1 with a device connected."
}

Write-Host "== clean =="
if (Test-Path $OUT) { Remove-Item $OUT -Recurse -Force }
New-Item -ItemType Directory -Path $OUT | Out-Null
New-Item -ItemType Directory -Path (Join-Path $OUT "classes") | Out-Null
New-Item -ItemType Directory -Path (Join-Path $OUT "dex") | Out-Null

Write-Host "== aapt2 =="
$resZip = Join-Path $OUT "res.zip"
& (Join-Path $BT "aapt2.exe") compile --dir (Join-Path $APP "res") -o $resZip
& (Join-Path $BT "aapt2.exe") link `
    -o (Join-Path $OUT "base.apk") `
    -I $AJAR `
    --manifest (Join-Path $APP "AndroidManifest.xml") `
    --min-sdk-version 29 `
    --target-sdk-version 34 `
    --version-code $VersionCode `
    --version-name $VersionName `
    -R $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 failed" }

Write-Host "== javac =="
$srcs = Get-ChildItem (Join-Path $APP "java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $AJAR -d (Join-Path $OUT "classes") $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== d8 =="
$classes = Get-ChildItem (Join-Path $OUT "classes") -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& (Join-Path $BT "d8.bat") --min-api 29 --lib $AJAR --output (Join-Path $OUT "dex") $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== assemble =="
& $PY (Join-Path $ROOT "assemble.py") `
    (Join-Path $OUT "base.apk") `
    (Join-Path $OUT "dex\classes.dex") `
    $LLAMA `
    $VENDOR `
    (Join-Path $OUT "unsigned.apk")
if ($LASTEXITCODE -ne 0) { throw "assemble failed" }

Write-Host "== zipalign =="
& (Join-Path $BT "zipalign.exe") -f -p 4 (Join-Path $OUT "unsigned.apk") (Join-Path $OUT "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

if (-not (Test-Path $KS)) {
    Write-Host "== generate debug keystore =="
    New-Item -ItemType Directory -Path (Split-Path $KS) -Force | Out-Null
    & (Join-Path $env:JAVA_HOME "bin\keytool.exe") -genkeypair `
        -keystore $KS -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Host "== sign =="
& (Join-Path $BT "apksigner.bat") sign `
    --ks $KS --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey `
    --v2-signing-enabled true --v3-signing-enabled true `
    --out (Join-Path $OUT "HexagonChat.apk") (Join-Path $OUT "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "== verify =="
& (Join-Path $BT "apksigner.bat") verify --print-certs (Join-Path $OUT "HexagonChat.apk")
& (Join-Path $BT "aapt2.exe") dump badging (Join-Path $OUT "HexagonChat.apk") | Select-Object -First 4

$final = Get-Item (Join-Path $OUT "HexagonChat.apk")
$hash = (Get-FileHash $final.FullName -Algorithm SHA256).Hash
Write-Host ""
Write-Host ("DONE  {0}  {1:N2} MB" -f $final.FullName, ($final.Length / 1MB))
Write-Host "SHA-256 $hash"
