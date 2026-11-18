#!/usr/bin/env bash
# Installs (or updates) Mirror Video so real cars' Android Auto lists it.
#
# Android Auto only shows a sideloaded navigation app when the Play Store is recorded as its installer.
# Android only records that when the install is started from adb (the shell), through the system
# installer with the Play Store's package name. `adb install -i com.android.vending` is not enough.
# Run this for the first install and for every update (an install from the phone resets it).
#
# Usage: tools/install-mirror-video.sh PitstopVideo-vX.Y.Z.mirror.apk   (phone connected with USB debugging)
# Windows: run it in Git Bash, or use tools/install-mirror-video.ps1.
set -eu
APK="${1:?usage: $0 PitstopVideo-vX.Y.Z.mirror.apk}"
ADB="${ADB:-adb}"
export MSYS_NO_PATHCONV=1   # Git Bash: keep /sdcard paths as they are
NAME="mirror-video-install.apk"
REMOTE="/sdcard/Download/$NAME"

"$ADB" push "$APK" "$REMOTE" >/dev/null
# Register the file with MediaStore: the system installer accepts content:// links, not file://.
"$ADB" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$REMOTE" >/dev/null
ID=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  ID=$("$ADB" shell content query --uri content://media/external/downloads --projection _id \
        --where "_display_name=\'$NAME\'" | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | tail -1 | tr -d '\r')
  [ -n "$ID" ] && break
  sleep 1
done
[ -n "$ID" ] || { echo "The phone didn't index $REMOTE; try again." >&2; exit 1; }

"$ADB" shell am start -a android.intent.action.INSTALL_PACKAGE \
  -d "content://media/external/downloads/$ID" -t application/vnd.android.package-archive \
  --ez android.intent.extra.NOT_UNKNOWN_SOURCE true \
  --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending >/dev/null
echo "Confirm the install on the phone. Afterwards, Mirror Video → Settings → Updates & about should say Ready."
