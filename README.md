# Pitstop Video

A video and music player for the car's screen. Video plays **only while parked**; music and
audio-only playback keep going while driving. It covers YouTube, Bilibili, PeerTube and videos on
the device, plus YouTube Music, NetEase Cloud Music, SoundCloud, Bandcamp, podcasts, radio,
Google Drive and live streams, with or without accounts.

Developed on a Pixel 9 Pro · Android 17 · Mazda CX-5 (Android Auto), and built to run on:

| Where | How |
|---|---|
| **Android Auto, any car brand** | Phone app projected to the car screen. Wide, portrait and small screens; touch, rotary knob and touchpad. |
| **Android Automotive OS** (Google built-in: Volvo, Polestar, GM, Honda, …) | Install the APK on the car. The app runs on the car screen; the car blocks video while driving. |
| **Aftermarket Android head units** | Install the APK on the unit and turn on *Account → This device is installed in a car* (GPS-based lock). |
| **Phones, tablets, foldables** | Android 8.0+. Side-by-side layout on tablets / landscape; picture-in-picture; split-screen. |

Not supported: Apple CarPlay / iOS (CarPlay does not allow third-party video apps), Huawei HiCar and Baidu CarLife (closed SDKs).

## Features

The app has three tabs, **Video**, **Music** and **Live**, each with a row of platforms. A button
at the end of the row (or *Settings → Platforms*) hides the ones you don't use; the car's browse
list follows the same choice.

### Platforms

| Tab | Platform | Without an account | With sign-in |
|---|---|---|---|
| Video | **YouTube** | Popular / Trending (endless, through every trending chart), categories, search | Google sign-in on Google's own page (nothing to set up): *Subscriptions*, *Liked videos*, *Watch later*, *History* |
| Video | **Bilibili** | Recommended, Popular, video zones, search (≤ 480–720p); multi-part videos (分P) become one entry per part | Web page or QR code: 1080p and the *Following* feed |
| Video | **PeerTube** | Instance feeds and categories, search, channels | – |
| Video | **On this device** | Every video in the device's MediaStore (Camera, Downloads, Movies…), offline | – |
| Music | **Bilibili Music** | Music charts and sub-zones, search | Higher audio quality |
| Music | **YouTube Music** | Charts, genres and moods, search | Same Google sign-in: *Liked music* and your playlists |
| Music | **NetEase Cloud Music** | Official charts, playlist tags, artist pages, search; big playlists play in full | Web page or QR code: *Daily picks* and your playlists |
| Music | **SoundCloud**, **Bandcamp** | Charts / discover tags, search, albums and playlists as folders | – |
| Music | **Podcasts** | Apple top charts and genres, search; shows open as folders of episodes | – |
| Music | **Radio** | radio-browser.info: popular, near you, genres, countries, languages | – |
| Music | **Google Drive** | *Pick from Google Drive* (Google's own picker) or *Add from files*; see [Google Drive](#google-drive) | Personal builds only: browse the whole Drive |
| Music | **On this phone** | Songs stored on the device | – |
| Live | **Bilibili Live** | Recommended, popular, live areas, search | *Following* |
| Live | **Twitch** | Top streams, top 100 categories, search; audio-only renditions | – |
| Live | **Douyu** | Popular, all categories, search | – |

Paid content is never unlocked: NetEase VIP songs and Bilibili paid trial clips are refused, not
played as previews. Bilibili songs that only play in mainland China are played from YouTube Music
or NetEase instead.

### Browsing and search

- Feeds, categories, search results and creator pages scroll endlessly.
- *Categories* opens a window with every category of the platform, grouped and filterable.
- Search suggests while you type and remembers recent searches. The *All platforms* chip searches
  every platform of the tab at once, with *More* for one platform's full results.
- *Sort & filter* on every list: sort by title, creator or length; filter by length, words or
  platform. Platform-side options too: YouTube upload date / length, Bilibili sort and length,
  uploader sort, radio sort.
- Tap a creator's name for their latest uploads; saved creators become feed chips on phone and car.

### Queue and playlists

- Long-press any item: *Play next*, *Add to queue*, *Download*, add to a playlist.
- Queue rows show thumbnails. Drag the ⋮⋮ grip on the left of a row to reorder; the list scrolls
  when you hold a row near the edge. Long-press a row to move it to the top or remove it;
  *Clear upcoming* empties what's left. Screen readers get *Move up* / *Move down* actions.
- Playlists mix every platform. Reorder them by dragging (in their own order), sort them and keep
  a sorted order, play or shuffle from the phone or the car.
- **Import** (Playlists tab): paste a link or a song list, or open a file. NetEase, YouTube /
  YouTube Music playlists and Bilibili favourites import as they are. QQ Music, Kugou, Spotify and
  Apple Music playlists, CSV files (Exportify, TuneMyMusic…) and "Artist - Title" text are matched
  song by song on the platforms you choose. Short share links are resolved first.
- **Export**: a re-importable Pitstop file (one playlist or all), CSV or plain text through the
  share sheet.

### Playback

- One player shared by phone and car: pick on either one, and with Android Auto connected the
  video goes to the car display.
- Shuffle (restorable) and repeat off / all / one.
- Quality button on the video (gear + current quality), with separate defaults for Wi-Fi and
  mobile data: Auto / 720p / 480p / 360p / *Audio only*. Audio only fetches just the sound and
  shows the cover like a song.
- Picks only streams the device can decode; on a decoder failure it retries once at 480p AVC.
  Reconnects with backoff at the same position, refreshes expired stream links, and steps quality
  down after repeated stalls. The next item is looked up in advance.
- Live streams join at the live edge.
- **Subtitles**: YouTube uploaded tracks, auto-generated captions and auto-translation into your
  language; Bilibili uploaded and AI subtitles (AI ones usually need sign-in). Style them in
  *Settings*: language, size, colour, text and background opacity, edge style, with a live preview.
- **Background playback**: notification, lock screen, headset and Bluetooth controls; picture-in-
  picture (automatic on Android 12+). The screen stays on while a video plays.
- **Data saver** on mobile data and hotspots: audio capped at about 128 kbps, a shorter buffer,
  no lookahead for the next track, and thumbnails at the size shown.

### Downloads

- *Download* in the player and in every item's menu: video at 1080 / 720 / 480 / 360p or audio
  only; songs as they are. Split video and audio streams are merged into one MP4 without
  re-encoding.
- Save to the phone (*Download/Pitstop* or a folder you pick) and / or to Google Drive (an
  app-made *Pitstop* folder, or one picked in Google's Drive picker).
- Runs in the background with a progress notification; cancel, retry or open finished files in
  *Settings*.
- Public builds don't save YouTube or YouTube Music videos (see [Build options](#build-options)).

### Car screen

- **Full screen**: video fills the visible area, with title and author beside Android Auto's
  buttons. **Split**: video beside (or, on portrait screens, above) title, progress and up next.
  Toggle with *Split / Full*.
- *Browse* opens as a side panel with the video shrunk beside it. *Queue* opens a side panel over
  the video; tap any entry (or Bilibili part) to jump to it. *CC* turns subtitles on and off.
- −10 s / +10 s buttons; tap the progress bar to jump, or drag it to scrub with a preview.
- **Parked-only lock**: when the car moves, video on the car screen is hidden. By default the sound
  keeps playing (audio-only); you can choose to pause instead. Music is not affected. Speed comes
  from the car, or GPS as a fallback. The lock can't be turned off.

### Look and language

- Pastel light theme or night theme (Light / Night / System), optional background picture from
  your own gallery, and a choice of player layout on narrow phones: a separate page with a mini bar,
  or a draggable floating window.
- English and Simplified Chinese, switched live on phone and car.
- Header avatar: the author's picture by default; a local `header_avatar` image replaces it in
  your own builds.

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
- SoundCloud, Bandcamp and PeerTube also come through NewPipeExtractor. NetEase uses its web API
  (weapi encryption), Twitch its HLS playlists, Douyu its signed web API.
- Every platform implements `core/VideoSource` (feeds, categories, search, creator pages), so the
  phone UI, the car screens and search treat them all the same way.

```
app/src/main/java/app/pitstop/video/
  core/       models, VideoSource interface, HTTP, settings, playlists, I18n (EN / ZH)
  youtube/    YouTube + YouTube Music sources, YouTubeAccount (web sign-in, feeds, RSS fallback), subtitles
  bilibili/   BiliApi, video / music / live sources, cookie jar, WBI signer, CDN choice
  netease/    NetEase Cloud Music API, crypto, source
  newpipe/    SoundCloud, Bandcamp, PeerTube
  podcast/    Apple charts + iTunes search, RSS parser
  radio/      radio-browser.info
  live/       Twitch, Douyu
  gdrive/     Drive picker (drive.file) and Drive source
  local/      videos and songs on the device (MediaStore)
  importer/   playlist import (NetEase, YouTube, Bilibili, QQ Music, Kugou, Spotify, Apple Music, CSV, text) and track matching
  download/   download service, stream merging, Drive upload
  player/     PlaybackHub, PlaybackService, StreamSelector, data saver, DrivingMonitor (parked-only lock)
  car/        CarAppService, screens, VirtualDisplay renderer + presentation
  ui/         Compose phone UI, sign-in pages, drag to reorder
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
| `pitstop.driveFullAccess=true` | Browse all of Google Drive after signing in (restricted `drive.readonly` scope, see *Google Drive*). |

## Put it on the car screen

Android Auto only shows apps that are not from the Play Store when developer mode allows it:

1. On the phone: **Settings → Connected devices → Connection preferences → Android Auto**.
2. Scroll down, tap **Version** ~10 times → accept developer mode.
3. ⋮ menu → **Developer settings** → enable **Unknown sources**.
4. Plug into the CX-5 (or connect wirelessly) and open *Pitstop Video* from the Android Auto launcher.

*Customize launcher* in the phone's Android Auto settings lists Pitstop Video only **while the phone
is connected to a car**. With no car connected, Android Auto can't verify an app that isn't from the
Play Store and leaves it out of that list (its log says `CAR.VALIDATOR: Package DENIED; failed all
other checks` and `CarInfo is null`). The app still appears in the car's app grid. To reorder or
hide it, open *Customize launcher* while connected.

When Pitstop opens on the car screen, it shows a reminder to keep your eyes on the road; if the
phone app is open at that moment, the phone shows it too.

Pitstop Video is listed in the Android Auto app grid (A–Z, after *Phone*). It draws its video on
Android Auto's map surface; use *Browse → Exit* to pause and close it. Switching to another car app pauses
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

*Music → Google Drive → Pick from Google Drive* opens Google's own Drive picker: open a folder and
select its songs (several at once). The app can see only the songs you picked (the non-sensitive
`drive.file` scope) and doesn't need the Google Drive app. Downloads can also be saved to Drive.
*Add from files* (the phone's file picker) stays as an alternative; it lists Drive only when the
Drive app is installed.

Publishing your own build? The picker needs a one-time Google Cloud setup for your signing key:
see [docs/google-drive-setup.md](docs/google-drive-setup.md).

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
- Privacy: the app collects nothing for the developer; see [PRIVACY.md](PRIVACY.md).

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
