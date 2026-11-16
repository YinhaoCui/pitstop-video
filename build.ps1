# Builds from a drive letter because the Android tools break on non-ASCII project paths.
# Usage: powershell -File build.ps1 [gradle tasks...]   (default: testParkedDebugUnitTest assembleDebug,
# which builds both flavors: app/build/outputs/apk/{parked,nav}/debug)
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Tasks)
if (-not $Tasks) { $Tasks = @('testParkedDebugUnitTest', 'assembleDebug') }
$drive = 'P:'
if (-not (Test-Path "$drive\")) { subst $drive "$PSScriptRoot" }
Push-Location "$drive\"
try { & .\gradlew.bat @Tasks } finally { Pop-Location }
