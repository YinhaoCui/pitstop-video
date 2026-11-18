# Installs (or updates) Mirror Video so real cars' Android Auto lists it. Windows version of
# install-mirror-video.sh (see there for why): run it for the first install and for every update.
# Usage: powershell -File tools/install-mirror-video.ps1 PitstopVideo-vX.Y.Z.mirror.apk   (phone connected, USB debugging on)
param([Parameter(Mandatory = $true)][string]$Apk)
$ErrorActionPreference = 'Stop'
$adb = if ($env:ADB) { $env:ADB } else { 'adb' }
$name = 'mirror-video-install.apk'
$remote = "/sdcard/Download/$name"

& $adb push $Apk $remote | Out-Null
if ($LASTEXITCODE -ne 0) { throw "adb push failed" }
# Register the file with MediaStore: the system installer accepts content:// links, not file://.
& $adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$remote" | Out-Null
$id = $null
for ($i = 0; $i -lt 10 -and -not $id; $i++) {
    $rows = & $adb shell "content query --uri content://media/external/downloads --projection _id --where `"_display_name='$name'`""
    $m = [regex]::Matches(($rows -join "`n"), '_id=(\d+)')
    if ($m.Count -gt 0) { $id = $m[$m.Count - 1].Groups[1].Value } else { Start-Sleep -Seconds 1 }
}
if (-not $id) { throw "The phone didn't index $remote; try again." }

& $adb shell am start -a android.intent.action.INSTALL_PACKAGE `
    -d "content://media/external/downloads/$id" -t application/vnd.android.package-archive `
    --ez android.intent.extra.NOT_UNKNOWN_SOURCE true `
    --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending | Out-Null
Write-Output 'Confirm the install on the phone. Afterwards, Mirror Video -> Settings -> Updates & about should say Ready.'
