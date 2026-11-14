# Google Drive setup (for whoever publishes the APK)

People installing a published release need nothing from this page. It is for whoever builds and
signs the APK: Google's Drive picker only opens for an app whose signing key is registered in a
Google Cloud project.

## One-time setup

1. In [Google Cloud Console](https://console.cloud.google.com/) create a project and enable the
   **Google Drive API**.
2. *OAuth consent screen*: user type **External**, add only the `.../auth/drive.file` scope, then
   **Publish app** (status *In production*). Non-sensitive scopes like this one don't go through
   Google's verification review and have no test-user limit; check the console's verification
   status after publishing. The consent screen needs a privacy policy link: use
   [PRIVACY.md](../PRIVACY.md).
3. *Credentials → Create credentials → OAuth client ID → Android*, package `app.pitstop.video`, one
   client per signing key:
   - the release key: `keytool -list -v -keystore pitstop-release.jks`
   - for your own debug builds, the debug key: `./gradlew signingReport`

No client ID goes into the code; Google matches the package name + SHA-1. Someone who builds the
app with their own key needs their own client, or uses *Add from files*.

If the key isn't registered, the picker closes right after the account choice and the app shows
*Drive picker failed: 16*.

## What `drive.file` allows

The app only sees files the user picked, or files it created itself (the *Pitstop* downloads
folder). Picking a folder gives access to the folder, not to the songs inside it, so the music
picker asks for songs: open a folder in the picker and select its songs, several at once.

## Whole-Drive browsing (personal builds only)

`pitstop.driveFullAccess=true` in `local.properties` lets a personal build browse the whole Drive
after signing in. That uses the restricted `drive.readonly` scope, which Google only allows for
test users unless the app passes a paid security assessment, so it is never on in the public
release.
