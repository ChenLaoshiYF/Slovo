$ErrorActionPreference = 'Stop'
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$emulator = Join-Path $sdk 'emulator/emulator.exe'
$mirror = Join-Path $env:LOCALAPPDATA 'Programs/Escrcpy/resources/extra/win/scrcpy/scrcpy.exe'
$apk = Join-Path (Split-Path (Split-Path $PSScriptRoot)) 'Slovo-0.8.0.apk'
function Get-StudyDevice {
    foreach ($entry in (& $adb devices)) {
        if ($entry -match '^(emulator-[0-9]+)\s+device$') {
            $deviceId = $Matches[1]
            $avdName = & $adb -s $deviceId emu avd name | Select-Object -First 1
            if ($avdName.Trim() -eq 'Tingye_API_34') { return $deviceId }
        }
    }
}
$deviceId = Get-StudyDevice
if (!$deviceId) {
    $argsList = @('-avd','Tingye_API_34','-no-boot-anim')
    if (Test-Path -LiteralPath $mirror) {
        $argsList += '-no-window'
        Start-Process -FilePath $emulator -ArgumentList $argsList -WindowStyle Hidden
    } else {
        Start-Process -FilePath $emulator -ArgumentList $argsList
    }
}
$deadline = (Get-Date).AddSeconds(180)
do {
    if (!$deviceId) { $deviceId = Get-StudyDevice }
    if ($deviceId -and ((& $adb -s $deviceId shell getprop sys.boot_completed) -join '').Trim() -eq '1') { break }
    Start-Sleep -Seconds 1
} while ((Get-Date) -lt $deadline)
if (!$deviceId) { throw 'Android emulator did not start.' }
& $adb -s $deviceId install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK install failed.' }
& $adb -s $deviceId shell wm dismiss-keyguard
& $adb -s $deviceId shell am start -n studio.tingye.reader/.WordActivity
if ((Test-Path -LiteralPath $mirror) -and !(Get-Process scrcpy -ErrorAction SilentlyContinue | Where-Object MainWindowTitle -eq 'Slovo - Android 14')) {
    $env:ADB = 'adb.exe'
    $env:PATH = (Join-Path $sdk 'platform-tools') + ';' + $env:PATH
    Start-Process -FilePath $mirror -ArgumentList @('--serial',$deviceId,'--window-title','"Slovo - Android 14"','--window-height','780') -WorkingDirectory (Split-Path $mirror) -WindowStyle Normal
}

