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
| **YouTube** | Popular + search without an account. Optional Google sign-in adds *Subscriptions* and *Liked videos*. |
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

- Android Auto only gives third-party apps a free drawing surface through the **navigation**
  template, so the app registers as a navigation-category car app
  (`androidx.car.app.category.NAVIGATION`). `CarVideoRenderer` receives that surface, creates a
  private `VirtualDisplay` on it and shows `CarPresentation` (a normal Android view tree with a
  Media3 `PlayerView`) there.
- Playback is one Media3 ExoPlayer in `PlaybackHub`, shared by phone and car.
- YouTube streams are resolved with [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor);
  personal feeds use the official YouTube Data API v3.
- Bilibili uses its public web API (WBI-signed), DASH video + audio merged by ExoPlayer.

```
app/src/main/java/app/pitstop/video/
  core/       models, VideoSource interface, HTTP, thumbnails
  youtube/    NewPipe downloader, YouTubeSource, YouTubeAccount (Data API sign-in)
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

## YouTube sign-in setup (optional)

Google requires an OAuth client registered for the app's signing key:

1. In [Google Cloud Console](https://console.cloud.google.com/) create a project and enable
   **YouTube Data API v3**.
2. Configure the OAuth consent screen (External, *Testing*), add your Google account as a test user.
3. Create credentials → **OAuth client ID → Android**: package `app.pitstop.video`, SHA-1 of the
   signing key (`./gradlew signingReport`).
4. In the app: *Account → Sign in with Google*.

No client ID needs to go into the code; Google matches the package name + SHA-1.

## Bilibili sign-in

- **Sign in (web)** opens Bilibili's own mobile login page (password / SMS) and keeps the session cookies.
- **Sign in (QR code)** shows a QR code to scan with the Bilibili app on another device, or opens the Bilibili app on this phone to confirm.

Cookies are stored in the app's private storage only.

Outside mainland China the app prefers Bilibili's Akamai servers and avoids its peer-to-peer
(PCDN) nodes, which stall badly abroad (`bilibili/BiliCdn.kt`).

## Caveats

- Personal, sideloaded use only. Extracting YouTube streams outside the official player is against
  YouTube's Terms of Service, and YouTube changes often; if YouTube playback breaks, update
  `newpipe` in `gradle/libs.versions.toml` to the latest NewPipeExtractor release.
- Google Play would not accept a video app in the navigation category; this app is meant to be
  installed directly.
- Watching video while driving is unsafe and illegal. The parked-only lock is on by design and
  cannot be turned off.
