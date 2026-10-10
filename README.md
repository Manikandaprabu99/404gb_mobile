# 404GB — Android app (sideloaded, personal use only)

A Capacitor wrapper around the deployed web app at https://404gb.vercel.app
(`capacitor.config.json` → `server.url`; the site is loaded live, not bundled),
plus one thing a web page can't do: **automatic background backup of the
phone's photos and videos**, Google Photos style, into your own GitHub-backed
404GB library.

This is a **debug build meant only for sideloading onto your own phone**. It is
not intended for the Play Store or anyone else's device.

## What gets backed up

- **Photos and videos**, both ones you take and ones you download or save
  (WhatsApp, browser downloads, screenshots…), depending on the folder setting:
  - **All photos & videos** — everything in the phone's media library.
  - **Camera only** — just what's under `DCIM/` (the camera roll).
- **Originals, untouched.** Files are uploaded byte-for-byte, including GPS
  location (the app asks for "access media location"; without it Android hands
  apps a copy with the location stripped).
- **No duplicates.** Every file is identified by its SHA-256. Anything already in
  the library — from this phone, another device or the web uploader — is
  skipped, and the phone keeps a local record of what it has handled.
- **Existing photos (optional).** When you turn backup on you choose whether to
  also back up everything already on the phone (newest first) or only items
  added from now on.

## How it works (and why it keeps working when the app is closed)

Backup runs natively in Android's WorkManager, not in the web page:

1. **Detection** — a WorkManager job watches the phone's photo and video
   collections and wakes up within seconds of anything new appearing, even when
   404GB is closed. A full safety rescan also runs every 6 hours. WorkManager
   re-registers these jobs after a reboot, so there is no always-on service.
2. **Ledger** — every photo/video the app has seen is recorded in a small local
   database. An item only counts as backed up after the server confirms it, so
   nothing can be silently skipped; edited-before-upload files are re-hashed.
3. **Upload** — files are streamed in 4 MiB chunks (never loaded into memory
   whole, so multi-GB videos are fine). Each uploaded chunk is remembered
   immediately, so an interrupted upload (lost signal, phone restart, Android
   pausing the app) resumes where it stopped instead of starting over. Preview
   thumbnails (320/800/1600 px) are made on the phone, including for HEIC
   photos and videos.
4. **Progress** — while uploading, a notification shows "Backing up 3 of 12"
   with the current file. The app's settings screen shows the same status.

The upload uses your normal 404GB sign-in (the app's session cookie). If the
session expires, backup pauses and a notification asks you to **sign in**;
opening the app and signing in resumes it.

### Settings

- **Wi-Fi only** — wait for an unmetered network (default on).
- **Only while charging** — wait until the phone is plugged in.
- **Folders** — all photos & videos, or camera only.
- **Back up now** / **Retry failed** — kick off a run or re-queue items that
  failed repeatedly.

When backup is waiting for Wi-Fi or charging, the status shows "waiting".
GitHub rate limits are handled the same way: the app waits and continues.

## Save to phone and share

From the gallery you can **save an original to the phone** (it appears in your
gallery under `Pictures/404GB` or `Movies/404GB`, with its original name) or
**share** it with any app. Both download the original chunk by chunk and verify
its SHA-256 before the file is used; a saved item is recorded as already backed
up, so it's never uploaded again.

## Permissions and why

| Permission | Why |
| --- | --- |
| Photos and videos (`READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`; storage on Android 12 and older) | Find and read the items to back up. Nothing on the phone is modified or deleted. |
| Media location (`ACCESS_MEDIA_LOCATION`) | Upload originals with their GPS data instead of a location-stripped copy. |
| Notifications (Android 13+) | Upload progress and the "sign in" alert. Optional: backup works without it. |
| Battery optimization exemption | Asked once. Lets Android run backups promptly and show the progress notification while the app is in the background. |
| Storage write (Android 9 and older only) | Saving originals into Pictures/Movies. |
| Foreground service (data sync), internet | Running long uploads reliably. |

**Android 14+ "Select photos and videos":** if you grant access to only some
items, backup can't see the rest. The status shows "needs permission"; turn
backup on again and choose **Allow all**.

## Getting the APK (no Android Studio needed)

GitHub Actions builds the APK in the cloud (`.github/workflows/android-apk.yml`)
on every push to `main` and `complete-app`. Only `main` builds are published to
the `android-latest` release; every run also keeps the APK as a build artifact
on the run's page. If a build fails, each compiler error is attached to the run
as an annotation.

1. On your phone, open
   https://github.com/Manikandaprabu99/404gb_mobile/releases/download/android-latest/404gb.apk
2. Your browser will warn that the file might be harmful — normal for any app
   from outside the Play Store. Download it anyway.
3. Open the downloaded file. If Android asks, allow your browser (or Files app)
   to **Install unknown apps**, then tap **Install**.
4. Open 404GB, sign in with GitHub, and turn on backup in the app.

To rebuild without changing code: repo → **Actions** → **Android APK** → **Run
workflow**.

**Updating:** install the new APK over the old one. Builds are signed with the
same debug key, kept in GitHub's Actions cache. GitHub drops caches unused for 7
days; if a build gets a new key, Android refuses the update ("App not
installed") — uninstall the old 404GB first, then install the new one and turn
backup back on.

## Building locally (alternative)

Prerequisites: Android Studio (current stable), JDK 21, Android SDK Platform 36
(`compileSdk`/`targetSdk` 36, `minSdk` 24 — Android 7.0+).

1. `npm install`, then `npx cap sync android` (regenerates files that aren't
   committed, including the config pointing at https://404gb.vercel.app).
2. Android Studio → **File → Open → android**, let Gradle sync.
3. Run on a USB-connected phone, or **Build → Build APK(s)** (output in
   `android/app/build/outputs/apk/debug/`).

### App icon and splash

The launcher icon (adaptive + themed + legacy), splash icon and notification
icon are generated from `brand/mark.svg` by `npm run assets`
(`scripts/generate-brand-assets.mjs`, uses `sharp`). Commit the regenerated
files under `android/app/src/main/res`.

## Code map

`android/app/src/main/java/com/fourzerofourgb/app/backup/`

- `BackupPlugin` — the `Backup` Capacitor plugin used by the web app
  (`lib/native/backup.ts`): enable/disable, options, status events, save/share.
- `ScanWorker`, `BackupScheduler` — content-URI-triggered and periodic scans.
- `MediaScanner`, `BackupLedger` — MediaStore → SQLite ledger reconciliation.
- `UploadWorker`, `MediaUploader`, `ApiClient`, `WebCookies`, `Thumbnails` —
  the upload engine.
- `MediaDownloader` — verified downloads for save-to-phone and share.

## Known limitations

- **Force-stopping the app** (Settings → Apps → 404GB → Force stop) cancels all
  background work until you open the app again — that's how Android treats
  force-stop. Swiping the app away from recents is fine.
- **Aggressive OEM battery managers** (some Samsung, Xiaomi, OnePlus… builds)
  can still delay background work. Allow the battery exemption when asked, or
  use **Battery settings** in the app and set 404GB to "Unrestricted".
- **Without the battery exemption**, Android may refuse the progress
  notification for uploads that start in the background; the upload still runs,
  but Android can pause it after about 10 minutes. It resumes automatically
  where it stopped.
- **Debug build:** no auto-updates, and uninstalling clears local state
  (sign-in, backup settings, the ledger). Already backed-up photos are safe in
  GitHub; after reinstalling, items already in the library are recognised by
  hash and not uploaded again.
