# 404GB — Android app (sideloaded, personal use only)

This is a thin Capacitor native wrapper around the deployed web app at
https://404gb.vercel.app. It loads the live site in a WebView
(`capacitor.config.json` → `server.url`) — it does **not** bundle a static
copy of the site — and adds one thing the web app can't do on its own:
a background watcher that notices new photos/videos on the phone and
uploads them automatically, using the existing chunked upload queue
(`lib/upload/uploadQueue.ts` in the 404GB_sourcecode web repo) exactly as-is.

This app is a **debug build, only meant to be sideloaded onto your own
device.** It is not intended for the Play Store or for anyone else's phone.

## Why a native wrapper at all

No pure PWA API can watch the Android (or iOS) photo library in the
background — a web page only gets to react to files the user explicitly
picks. Reading the camera roll continuously and running while the app
isn't in the foreground both require a real native component: a
`ContentObserver` on Android's `MediaStore`, running inside a foreground
`Service`. That's what `android/app/src/main/java/com/fourzerofourgb/app/
photowatcher/` adds.

## Getting the APK (no Android Studio needed)

GitHub Actions builds the APK in the cloud on every push to `main` (see
`.github/workflows/android-apk.yml`). Nothing has to be installed on your
computer.

1. On your phone, open
   https://github.com/Manikandaprabu99/404gb_mobile/releases/download/android-latest/404gb.apk
2. Your browser will warn that the file might be harmful — that's normal
   for any app from outside the Play Store. Download it anyway.
3. Open the downloaded file. If Android asks, allow your browser (or Files
   app) to **Install unknown apps**, then tap **Install**.
4. Open 404GB, sign in with GitHub, go to **Upload**, and turn on
   **Auto-backup camera roll**.

To rebuild without changing any code: repo → **Actions** → **Android APK**
→ **Run workflow**. Every run also keeps a copy of the APK as a build
artifact on the run's page.

**Updating:** install the new APK over the old one. Every build is signed
with the same debug key, which the workflow keeps in GitHub's Actions
cache. GitHub drops caches that go unused for 7 days, so if you haven't
built in over a week, the next build gets a new key and Android will refuse
to install it over the old app ("App not installed"). In that case
uninstall the old 404GB first, install the new one, then sign in and turn
auto-backup back on.

## Building locally with Android Studio (alternative)

Prerequisites:

- **Android Studio** (current stable)
- **JDK 21** — Capacitor 8 compiles with Java 21 (Android Studio's bundled
  JDK works)
- **Android SDK** Platform 36 + build tools, via Android Studio's SDK
  Manager (`compileSdk`/`targetSdk` 36, `minSdk` 24 — Android 7.0+)
- A phone connected over USB with **Developer options → USB debugging**
  enabled

Steps:

1. In the repo root, run `npm install` and then `npx cap sync android`. The
   sync step regenerates files that aren't committed (including the config
   that points the app at https://404gb.vercel.app), so don't skip it.
2. In Android Studio: **File → Open → android**, and let Gradle sync
   (needs internet the first time).
3. Pick your phone in the device dropdown and click **Run ▶**. Or, for a
   standalone APK: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**,
   output in `android/app/build/outputs/apk/debug/`.

## What it'll ask permission for, and why

The first time you turn on **Auto-backup camera roll**:

- **Photos and videos** (`READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` on
  Android 13+, or the older storage permission on Android 12 and below) —
  needed to find new photos/videos in `MediaStore` and read their bytes to
  upload them. Nothing is deleted or modified.
- **Notifications** (Android 13+) — Android requires an ongoing,
  low-priority notification while the watcher's foreground service runs,
  so you always know it's active. If you deny it, the watcher still works;
  the notification just isn't shown.
- **Run in background without battery restrictions** — lets the watchdog
  restart the watcher if Android stops it. Allow this for reliable backup.

## Known, honest limitations

- **OEM battery optimization can still delay or kill the watcher.**
  Samsung, Xiaomi, OnePlus, and several other manufacturers ship
  aggressive battery managers that kill background work regardless of how
  the app is written. A `WorkManager` job checks roughly every 15 minutes
  (the platform minimum) and restarts the watcher if it was killed, but
  that's a recovery net, not a guarantee of zero gaps. For reliability, also
  check **Settings → Apps → 404GB → Battery** and set it to "Unrestricted"
  / "No restrictions" (wording varies by manufacturer).
- **New photos only, not a backfill.** The watcher starts from the moment
  you enable it; it doesn't upload your existing library. Use the manual
  multi-select upload on the Upload page for that.
- **Force-stopping the app stops the watcher.** Force-stop from Android's
  app-info screen (not just closing the app) stops the foreground service;
  open the app again to resume watching.
- **Debug build:** no auto-updates, and uninstalling clears the app's local
  state (sign-in, auto-backup setting). Your photos already backed up to
  GitHub are unaffected.
