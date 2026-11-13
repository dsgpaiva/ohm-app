# Starts the "Ohm Head Unit" emulator (1280x720 landscape), installs the newest Ohm APK from dist\
# and opens it. Usage (from the project folder):  powershell -ExecutionPolicy Bypass -File tools\run-emulator.ps1
$ErrorActionPreference = "Stop"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$emu = "$sdk\emulator\emulator.exe"
$root = Split-Path $PSScriptRoot -Parent

$running = & $adb devices | Select-String "emulator-\d+\s+device"
if (-not $running) {
    Write-Host "Starting emulator 'Ohm_Head_Unit'..."
    Start-Process $emu -ArgumentList "-avd", "Ohm_Head_Unit", "-gpu", "auto", "-no-boot-anim"
    & $adb wait-for-device
    do { Start-Sleep 2; $boot = (& $adb shell getprop sys.boot_completed 2>$null) } until ($boot -match "1")
}

$apk = Get-ChildItem "$root\dist\Ohm-*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "No Ohm APK in dist\ — build one first." }
Write-Host "Installing $($apk.Name)..."
& $adb install -r $apk.FullName
# Grant location so the map works straight away, then open Ohm.
& $adb shell pm grant com.ohm.launcher android.permission.ACCESS_FINE_LOCATION 2>$null
& $adb shell am start -n com.ohm.launcher/.MainActivity
Write-Host "Done. Tip: Extended controls (…) › Location lets you set or replay a GPS route."
