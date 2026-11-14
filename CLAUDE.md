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
- Commits: small and focused; run unit tests + assembleDebug before committing.
