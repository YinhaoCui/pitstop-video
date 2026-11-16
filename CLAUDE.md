# Pitstop Video – agent notes

Android Auto video player (YouTube / Bilibili / local files), single `:app` module, Kotlin, Compose phone UI,
Car App Library 1.7 for the car. See README.md for the architecture overview.

## Build & test

- The project folder path contains non-ASCII characters; build through a drive mapping:
  `powershell -File build.ps1` (or `subst P: <project dir>` then run Gradle from `P:\`).
- `./gradlew testParkedDebugUnitTest assembleDebug` — builds both flavors (`parked`, `nav`; see below). JVM unit tests live in `app/src/test` and only cover
  pure logic (WBI signing, stream selection, motion latch, parsing). Keep Android types out of those classes.

## Rules

- `PlaybackHub` owns the only ExoPlayer. Phone and car views attach/detach their surface; the car wins
  while `HubState.carActive` is true.
- The parked-only lock (`DrivingMonitor` → `HubState.drivingLocked`) must stay mandatory: never add a
  setting that lets *video* show on a car screen while moving. Audio-only mode (video track disabled,
  picture hidden) is the only allowed alternative to pausing.
- All user-visible text lives in `core/I18n.kt` (English + Simplified Chinese); use `S.x` in Compose,
  `I18n.s.x` elsewhere. Never hard-code UI strings.
- Every source implements `core/VideoSource`; add new feeds there instead of special-casing the UI.
- Two product flavors, same application id, both in every release (`PitstopVideo-*.apk` "Pitstop
  Video" and `MirrorVideo-*.apk` "Mirror Video", its own name via `src/nav/res` and `I18n.appName`;
  the updater picks its own via `Updates.isNavApk`, `BuildConfig.NAV_ROUTE`):
  - `parked`: parked app + POI template app (`MAP_TEMPLATES`); car root is `MapWithContentTemplate`.
  - `nav`: navigation template app only (`NAVIGATION_TEMPLATES`, parked activity disabled); car root
    is `NavigationTemplate`, so the video is full screen. Panels stay `MapWithContentTemplate`.
  Car templates need car API 7+; respect host limits (max 4 actions per strip, list length from
  `ConstraintManager`).
- Android Auto 18 refuses a Car App Library service on real cars (`CAR.VALIDATOR: Package DENIED;
  failed all other checks`) unless `installingPackageName` is `com.android.vending`. Unknown sources,
  the category or a debuggable build don't matter. `adb install -i com.android.vending` fails (the
  install is initiated by `com.android.shell`); what works (confirmed on a real car, AA 18.0) is the
  system installer intent `ACTION_INSTALL_PACKAGE` with `EXTRA_NOT_UNKNOWN_SOURCE` and
  `EXTRA_INSTALLER_PACKAGE_NAME=com.android.vending` (Fermata's trick) **sent from adb**
  (`tools/install-mirror-video.sh` / `.ps1`). Sent by the app itself the extra is ignored
  (installer becomes `com.google.android.packageinstaller`, tested on Android 17), so Mirror Video
  can't fix or update itself: its updater only links the release page (`AppUpdater.canInstall`),
  and any install on the phone hides it from AA again. `update/PlayInstall.kt` reports the state.
  The script pushes the APK to `/sdcard/Download` and uses its MediaStore `content://` id
  (`file://` URIs don't resolve; set `MSYS_NO_PATHCONV=1` in Git Bash).
  The DHU is a *developer head unit* that skips this check, so "it shows on the DHU" proves
  nothing about a real car. The honest test without a car: AA settings → Customize launcher with
  nothing connected.
- What real cars accept for any install is the **parked app** route (confirmed on a real car):
  `CarDisplayActivity` (a `MainActivity` subclass in its own task) has `CAR_LAUNCHER` and the app
  declares `android:appCategory="game"` (`video` is refused: `Not allowed native app`). AA shows
  that activity on a virtual display while parked and closes it when driving. The phone's
  `MainActivity` can be open at the same time: `HubState.onCarDisplay` gives the car activity the
  video surface (`HubState.shownElsewhere()` turns the phone into a remote) and extends the
  parked-only lock to it. `Settings.carVideoOnly` picks video-only vs the whole app on the car.
  Keep both routes; AA lists one icon per package (template wins where it is allowed).
- A shell `am start` / `monkey` launch lands on the last-touched display (the car's, after a DHU
  tap); use `am start --display 0 -n .../.ui.MainActivity` to open the phone instance in tests.
- DHU: AA → ⋮ → Start head unit server, `adb forward tcp:5277 tcp:5277`, run
  `extras/google/auto/desktop-head-unit.exe` (end it with its `quit` command, not by killing it, or
  AA stays "Already connected"; `restrict all` simulates driving). To test the parked route there,
  build a copy with the car service disabled (`android:enabled="false"` on `VideoCarAppService`). `-Ppitstop.idSuffix=.dev` installs a test copy beside
  the release.
- Google Drive picker (`drive.file`): picking a folder grants the folder, not the files in it, so the
  music picker asks for songs only; folder picking is kept for the downloads destination.
- Commits: small and focused; run unit tests + assembleDebug before committing.
