# Pitstop Video privacy policy

Last updated: 14 November 2026

Pitstop Video is an open-source Android and Android Auto media player published at
<https://github.com/YinhaoCui/pitstop-video>. This policy explains what the app does with your data.

## The short version

The developer runs no servers and collects nothing. The app has no analytics, advertising or
crash-reporting libraries. Everything it stores stays in the app's private storage on your phone,
and uninstalling the app deletes it.

## Google user data (Google Drive)

When you choose *Pick from Google Drive* or save a download to Drive, the app asks for the
`drive.file` permission. With it, the app can see only the files and folders you pick in Google's
Drive picker and the files it creates itself (for example a "Pitstop" folder for downloads). It
cannot see the rest of your Drive.

- **What is accessed:** the names and contents of the files you pick, and files the app creates.
- **How it is used:** only to play those files or save your downloads, on your phone.
- **Where it goes:** directly between your phone and Google. Nothing is sent to the developer or
  anyone else, and Drive data is never sold, used for advertising or used to train AI models.
- **Storage:** the sign-in token and the list of picked files are kept in the app's private storage.
- **Revoking access:** sign out in the app, or remove Pitstop Video at
  <https://myaccount.google.com/permissions>.

The app's use of information received from Google APIs follows the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including its Limited Use requirements.

## Other accounts and services

If you sign in to YouTube or Bilibili, the app keeps the sign-in session (token or cookies) in its
private storage and sends it only to that service, to load your own feeds. Searches and playback
requests go straight to the service you are using (YouTube, Bilibili and others), whose own
privacy policies apply.

## Location

The app reads the phone's GPS speed only as a fallback for the parked-only safety lock, when the car
does not report its speed. The location is used on the phone at that moment and is never stored or
sent anywhere.

## Media on your phone

Local videos and music you open are read on the phone only.

## Children

The app is not directed at children under 13.

## Changes and contact

Changes to this policy are published in this file, and its history is in the repository. For
questions, open an issue at <https://github.com/YinhaoCui/pitstop-video/issues>.
