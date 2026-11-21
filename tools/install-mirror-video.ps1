# Installs (or updates) Mirror Video so real cars' Android Auto lists it. Windows version of
# install-mirror-video.sh (see there for why): run it for the first install and for every update.
# Usage: powershell -File tools/install-mirror-video.ps1 [PitstopVideo-vX.Y.Z.mirror.apk]   (phone connected, USB debugging on)
# Without a file it downloads the newest release's Mirror Video APK.
param([string]$Apk)
$ErrorActionPreference = 'Stop'
$adb = if ($env:ADB) { $env:ADB } else { 'adb' }
$repo = if ($env:PITSTOP_REPO) { $env:PITSTOP_REPO } else { 'YinhaoCui/pitstop-video' }
if (-not $Apk) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $release = Invoke-RestMethod "https://api.github.com/repos/$repo/releases/latest" -Headers @{ 'User-Agent' = 'install-mirror-video' }
    $asset = $release.assets | Where-Object { $_.name -like '*.mirror.apk' } | Select-Object -First 1
    if (-not $asset) { throw "No Mirror Video APK in the newest release of $repo." }
    $Apk = Join-Path $env:TEMP $asset.name
    Write-Output "Downloading $($asset.name)..."
    Invoke-WebRequest $asset.browser_download_url -OutFile $Apk -UseBasicParsing
}
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

# --grant-read-uri-permission: without it the installer may get no access to the file and silently close.
& $adb shell am start --grant-read-uri-permission -a android.intent.action.INSTALL_PACKAGE `
    -d "content://media/external/downloads/$id" -t application/vnd.android.package-archive `
    --ez android.intent.extra.NOT_UNKNOWN_SOURCE true `
    --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending | Out-Null
Write-Output 'Confirm the install on the phone. Afterwards, Mirror Video -> Settings -> Updates & about should say Ready.'
