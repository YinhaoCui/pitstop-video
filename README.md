# Pitstop Video

A video player for watching videos on a car's screen **while parked**:
YouTube, Bilibili (signed in or not), and offline videos stored on the device.

Developed on a Pixel 9 Pro · Android 17 · Mazda CX-5 (Android Auto), and built to run on:

| Where | How |
|---|---|
| **Android Auto, any car brand** | Phone app projected to the car screen. Wide, portrait and small screens; touch, rotary knob and touchpad. |
| **Android Automotive OS** (Google built-in: Volvo, Polestar, GM, Honda, …) | Install the APK on the car. The app runs on the car screen; the car blocks video while driving. |
| **Aftermarket Android head units** | Install the APK on the unit and turn on *Account → This device is installed in a car* (GPS-based lock). |
| **Phones, tablets, foldables** | Android 8.0+. Side-by-side layout on tablets / landscape; picture-in-picture; split-screen. |

Not supported: Apple CarPlay / iOS (CarPlay does not allow third-party video apps), Huawei HiCar and Baidu CarLife (closed SDKs).

## Features

| | |
|---|---|
| **YouTube** | Popular + search without an account. Optional Google sign-in (nothing to set up) adds *Subscriptions*, *Liked videos*, *Watch later* and *History*; YouTube Music gets *Liked music* and your playlists. |
| **Bilibili** | Recommended / Popular / search without an account (≤ 480–720p). Sign in (web page or QR code) for 1080p and the *Following* feed. Multi-part videos (分P) expand into one queue entry per part. |
| **Offline** | Every video in the device's MediaStore (Camera, Downloads, Movies…). No network needed. |
| **Subtitles** | YouTube: uploaded tracks, auto-generated in your language, and auto-translation into your language. Bilibili: uploaded and AI subtitles (AI ones usually need login). *CC* on the phone player; *Queue → CC* on the car screen cycles Off → languages. |
| **Queue** | Car: *Queue* opens a side panel over the playing video; tap any entry (or Bilibili part) to jump to it. Phone: queue button in the now-playing bar. |
| **Playlists** | Mix YouTube, Bilibili and offline videos; play or shuffle from the phone or the car. |
| **Car: full screen** | Video fills the visible screen; title and author show with Android Auto's buttons. |
| **Car: split screen** | Video beside (or, on portrait screens, above) title / progress / up-next. Toggle with *Split / Full*. |
| **Parked-only lock** | When the car moves, car-screen video pauses and is hidden. Speed comes from the car, or GPS as a fallback. |
| **Device-aware quality** | Picks only streams the device can decode; on a decoder failure it retries once at 480p AVC. |

Pick a video on the car screen (*Browse*) or on the phone — the same player is shared, and
when Android Auto is connected the video goes to the car display.

## How it works

- Android Auto gives the app a drawing surface through `MapWithContentTemplate` (car API 7+): the
  app registers as a point-of-interest car app with the `MAP_TEMPLATES` permission
  (`androidx.car.app.category.POI`). Android Auto hides sideloaded *navigation* apps even with
  Unknown sources on, so the navigation category isn't used. `CarVideoRenderer` receives that surface, creates a
  private `VirtualDisplay` on it and shows `CarPresentation` (a normal Android view tree with a
  Media3 `PlayerView`) there.
- Playback is one Media3 ExoPlayer in `PlaybackHub`, shared by phone and car.
- YouTube streams are resolved with [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor).
  Personal feeds come from YouTube's web API with the session of a Google sign-in done on Google's
  own page (`youtube/YouTubeAccount.kt`); no API key, Cloud project or quota. Playback never uses
  that session. Subscribed channels are remembered, and *Subscriptions* falls back to their public
  RSS feeds if the web API changes or the session ends.
- Bilibili uses its public web API (WBI-signed), DASH video + audio merged by ExoPlayer.

```
app/src/main/java/app/pitstop/video/
  core/       models, VideoSource interface, HTTP, thumbnails
  youtube/    NewPipe downloader, YouTubeSource, YouTubeAccount (web sign-in, feeds, RSS fallback)
  bilibili/   BiliApi, BiliSource, cookie jar, WBI signer
  local/      LocalSource (MediaStore)
  player/     PlaybackHub, StreamSelector, DrivingMonitor (parked-only lock)
  car/        CarAppService, Session, screens, VirtualDisplay renderer + presentation
  ui/         Compose phone UI, Bilibili WebView login
```

## Build

Requirements: JDK 17+ (21 used), Android SDK platform 37. The app itself runs on Android 8.0 (API 26) and newer.

```bash
./gradlew testDebugUnitTest assembleDebug
```

> The Android build tools fail on paths with non-ASCII characters. If the checkout lives under
> e.g. `OneDrive\桌面`, map it to a drive letter first (`build.ps1` does this):
> `subst P: "<project folder>"` and build from `P:\`.

Install on the phone: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

### Releases

Pushing a version tag (`git tag v0.6.0 && git push origin v0.6.0`) runs
`.github/workflows/release.yml`: it runs the unit tests, builds a release APK signed with your own
key and attaches it to a GitHub release. One-time setup:

1. Create a key (keep it safe; every future update must be signed with it):
   `keytool -genkeypair -v -keystore pitstop-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias pitstop`
2. In the repository: *Settings → Secrets and variables → Actions*, add `KEYSTORE_BASE64`
   (`base64 -w0 pitstop-release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

Installing the release over a debug build fails (different key): uninstall the debug build first.

### Build options

Off by default, and off in the public release. Turn them on for your own build in
`local.properties` (or with `-P` on the Gradle command line):

| Property | Effect |
|---|---|
| `pitstop.youtubeDownloads=true` | Allows saving YouTube / YouTube Music videos to the device. |
| `pitstop.googleCloud=true` | Google Drive sign-in, Drive picker and uploads (needs your own OAuth client, see *Google Drive*). |

## Put it on the car screen

Android Auto only shows apps that are not from the Play Store when developer mode allows it:

1. On the phone: **Settings → Connected devices → Connection preferences → Android Auto**.
2. Scroll down, tap **Version** ~10 times → accept developer mode.
3. ⋮ menu → **Developer settings** → enable **Unknown sources**.
4. **Customize launcher** → make sure *Pitstop Video* is ticked.
5. Plug into the CX-5 (or connect wirelessly) and open *Pitstop Video* from the Android Auto launcher.

Pitstop Video is listed in the Android Auto app grid (A–Z, after *Phone*). Because Android Auto
treats it as a navigation app it stays in the map card until another navigation app replaces it;
use the **✕** button (bottom right) to pause and close it. Switching to another car app pauses
the video.

Mazda Connect notes: the touchscreen only works while parked (by Mazda's design); the commander
knob moves between the on-screen buttons (*Browse*, play/pause, *Split/Full*, next, ±10 s).
The on-screen keyboard for car search is also parked-only — or search on the phone, which plays
straight onto the car screen.

## YouTube sign-in

*Settings → YouTube → Sign in with Google* opens Google's own sign-in page. When it returns to
YouTube, the app keeps the youtube.com session cookies in its private storage and uses them only
to list your feeds. Nothing needs to be registered with Google, so this works in any build.

If Google says *"This browser or app may not be secure"*, try again later or from another network;
Google sometimes blocks sign-in pages it thinks are embedded. *Subscriptions* keeps working from the
remembered channels while signed out.

## Google Drive

Public builds: *Music → Google Drive → Add songs* uses the system file picker; the Drive app
serves the picked files, with no sign-in.

Personal builds can also browse Drive folders, use Google's Drive picker and upload downloads to
Drive. These need your own OAuth client, so they are off unless you build with
`pitstop.googleCloud=true` (see *Build options*):

1. In [Google Cloud Console](https://console.cloud.google.com/) create a project and enable the **Google Drive API**.
2. Configure the OAuth consent screen (External, *Testing*) and add your Google account as a test user.
3. Create credentials → **OAuth client ID → Android**: package `app.pitstop.video`, SHA-1 of the
   signing key (`./gradlew signingReport`).

No client ID goes into the code; Google matches the package name + SHA-1.

## Bilibili sign-in

- **Sign in (web)** opens Bilibili's own mobile login page (password / SMS) and keeps the session cookies.
- **Sign in (QR code)** shows a QR code to scan with the Bilibili app on another device, or opens the Bilibili app on this phone to confirm.

Cookies are stored in the app's private storage only.

Outside mainland China the app prefers Bilibili's Akamai servers and avoids its peer-to-peer
(PCDN) nodes, which stall badly abroad (`bilibili/BiliCdn.kt`).

## Caveats

- YouTube changes often; if YouTube playback breaks, update `newpipe` in
  `gradle/libs.versions.toml` to the latest NewPipeExtractor release.
- Google Play would not accept this app; it is meant to be installed directly.
- Watching video while driving is unsafe and illegal. The parked-only lock is on by design and
  cannot be turned off.

## Legal

- Pitstop Video is an independent project. It is not affiliated with, endorsed by or sponsored by
  Google, YouTube, Bilibili, NetEase, Twitch, Douyu or any other service it can open. Their names
  are used only to say which service a feature works with; the app uses no logos or other brand
  assets of theirs.
- The app hosts no content. It plays what each service serves to a normal visitor or to your own
  account, and you are responsible for following each service's terms.
- Paid content is never unlocked: songs and videos that need a purchase or membership you don't
  have are refused (NetEase VIP songs, Bilibili paid trial clips), not played as previews.
- Public builds do not save YouTube or YouTube Music videos to the device.
- Rights holders with a concern can open an issue on this repository.

## License

Copyright (C) 2026 Yinhao Cui

Pitstop Video is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. It is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

In short: anyone may use, study, change and share the app. Anyone who distributes it, changed or
not, must do so under the same license and make the complete source code available.

It includes [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0) and
other open-source libraries under their own compatible licenses.
