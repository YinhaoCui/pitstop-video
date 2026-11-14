# Pitstop Video – agent notes

Android Auto video player (YouTube / Bilibili / local files), single `:app` module, Kotlin, Compose phone UI,
Car App Library 1.7 for the car. See README.md for the architecture overview.

## Build & test

- The project folder path contains non-ASCII characters; build through a drive mapping:
  `powershell -File build.ps1` (or `subst P: <project dir>` then run Gradle from `P:\`).
- `./gradlew testDebugUnitTest assembleDebug` — JVM unit tests live in `app/src/test` and only cover
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
- Car templates: the root is a `MapWithContentTemplate` (its map surface is where the video is drawn). The app is
  a POI app with `MAP_TEMPLATES`, not navigation: Android Auto hides sideloaded navigation apps even with
  Unknown sources on (its log: `CAR.VALIDATOR: Package DENIED; failed all other checks`). Needs car API 7+.
  Respect host limits (max 4 actions per strip, list length from `ConstraintManager`).
- Android Auto 18 refuses the sideloaded Car App Library service on real cars (`CAR.VALIDATOR:
  Package DENIED; failed all other checks`): Unknown sources doesn't cover template apps, and the
  category (NAVIGATION / POI), a debuggable build or `adb install -i com.android.vending` change
  nothing. The DHU is a *developer head unit* that skips this check, so "it shows on the DHU" proves
  nothing about a real car. The honest test without a car: AA settings → Customize launcher with
  nothing connected.
- What real cars accept is the **parked app** route (confirmed on the Mazda CX-5):
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
