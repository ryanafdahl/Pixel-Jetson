param(
  [Parameter(Mandatory=$true)][string]$Serial,
  [Parameter(Mandatory=$true)][string]$Bench,
  [string]$Adb = (Join-Path $env:TEMP 'codex-pixel-tools\platform-tools\adb.exe')
)
$ErrorActionPreference = 'Stop'
function Invoke-PixelAdb {
  & $Adb -s $Serial @args
  if ($LASTEXITCODE -ne 0) { throw "ADB operation failed: $($args[0])" }
}
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'app/src/main/assets/model.json') -Raw | ConvertFrom-Json
$deviceSoc = (& $Adb -s $Serial shell getprop ro.soc.model).Trim()
if ($deviceSoc -ne $manifest.soc) { throw "Model targets $($manifest.soc); connected device is $deviceSoc" }
$socName = $manifest.soc.Replace(' ', '_')
$modelFile = Join-Path $Bench "model-static-shapes-apply-$socName.tflite"
if ((Get-Item -LiteralPath $modelFile).Length -ne $manifest.compiled_bytes) { throw 'Model length mismatch' }
if ((Get-FileHash -LiteralPath $modelFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.compiled_sha256) { throw 'Model checksum mismatch' }
Invoke-PixelAdb install -r (Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk')
Invoke-PixelAdb shell am force-stop net.afdahl.jetlink.pixel
Invoke-PixelAdb shell mkdir -p /data/local/tmp/jetlink-pixel-install/fixture
Invoke-PixelAdb push $modelFile /data/local/tmp/jetlink-pixel-install/model.tflite
Invoke-PixelAdb push (Join-Path $Bench 'runtime/driving-input-padded/.') /data/local/tmp/jetlink-pixel-install/fixture/
Invoke-PixelAdb shell run-as net.afdahl.jetlink.pixel mkdir -p files/fixture
Invoke-PixelAdb shell "cat /data/local/tmp/jetlink-pixel-install/model.tflite | run-as net.afdahl.jetlink.pixel sh -c 'cat > files/model.tflite'"
foreach ($tensorName in @('img','big_img','desire_pulse','traffic_convention','action_t','features_buffer')) {
  Invoke-PixelAdb shell "cat /data/local/tmp/jetlink-pixel-install/fixture/$tensorName.raw | run-as net.afdahl.jetlink.pixel sh -c 'cat > files/fixture/$tensorName.raw'"
}
Invoke-PixelAdb install -r (Join-Path $PSScriptRoot 'app/build/outputs/apk/release/app-release.apk')
Invoke-PixelAdb shell cmd package compile -m speed -f net.afdahl.jetlink.pixel
Invoke-PixelAdb shell input keyevent KEYCODE_WAKEUP
Invoke-PixelAdb shell wm dismiss-keyguard
Invoke-PixelAdb shell am start -n net.afdahl.jetlink.pixel/.MainActivity
Write-Output 'Installation complete. Unlock the phone if needed and keep JetLink Pixel Test visible.'
