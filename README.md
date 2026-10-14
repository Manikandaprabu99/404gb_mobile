# 404GB — Android app (sideloaded, personal use only)

A standalone Android app for 404GB, your own Google-Photos-style gallery whose
storage is a GitHub repo you own. **No server is involved:**

- **The UI** is the 404GB web app ([Manikandaprabu99/404GB_sourcecode](https://github.com/Manikandaprabu99/404GB_sourcecode))
  built as a static export and bundled into the APK (Capacitor serves it from
  `https://localhost`).
- **Your photos** are read from and written to your GitHub repo directly with
  your own GitHub token, by both the UI and the background backup.
- **Background backup** of the phone's photos and videos runs natively, Google
  Photos style, and keeps going while the app is closed.

The repo format is the same as before (`objects/`, `thumbnails/`, `metadata/`,
`manifest/media-index.json`), so existing libraries keep working. The design
and the shared contract live in the web repo's `docs/STANDALONE.md`.

This is a **debug build meant only for sideloading onto your own phone**. It is
not intended for the Play Store or anyone else's device.

## Signing in (pick one)

The app needs a GitHub token that can read and write your photo repo:

1. **"Sign in with GitHub" (Device Flow).** Needs a GitHub OAuth App:
   GitHub → Settings → Developer settings → OAuth Apps → New OAuth App (any
   homepage/callback URL), then tick **Enable Device Flow** in its settings.
   Put the app's **Client ID** (not a secret) into
   [`config/github-client-id`](config/github-client-id) — the whole file is just
   the id — and push; CI builds it into the app. While that file is empty the
   app hides this option.
2. **A personal access token**, pasted into the app. Either a classic token
   with the `repo` scope
   ([create one](https://github.com/settings/tokens/new?scopes=repo&description=404GB)),
   or a fine-grained token with **Contents: read and write** (plus Metadata:
   read) on your photo repo.

The token is stored only on the phone, in app-private storage that is excluded
from cloud backups and device transfers. Signing out forgets it on this phone
(GitHub offers no way to revoke a Device Flow token without the OAuth App's
secret; revoke it on GitHub under Settings → Applications if you need to). If
GitHub stops accepting the token, backup pauses and a notification asks you to
sign in again.

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

## How backup works (and why it keeps working when the app is closed)

Backup runs natively in Android's WorkManager, not in the web page:

1. **Detection** — a WorkManager job watches the phone's photo and video
   collections and wakes up within seconds of anything new appearing, even when
   404GB is closed. A full safety rescan also runs every 6 hours. WorkManager
   re-registers these jobs after a reboot, so there is no always-on service.
2. **Ledger** — every photo/video the app has seen is recorded in a small local
   database. An item only counts as backed up after its commit landed on
   GitHub, so nothing can be silently skipped; edited-before-upload files are
   re-hashed.
3. **Upload** — files are streamed in 4 MiB chunks straight to GitHub as git
   blobs (never loaded into memory whole, so multi-GB videos are fine). Each
   uploaded chunk is remembered immediately, so an interrupted upload (lost
   signal, phone restart, Android pausing the app) resumes where it stopped.
   Preview thumbnails (320/800/1600 px) are made on the phone, including for
   HEIC photos and videos.
4. **Commit** — a few ready items at a time go into one commit (tree, commit,
   then a fast-forward-only branch update), built on the latest state of the
   repo; if another device committed meanwhile, the app rebuilds on top of that
   and tries again, re-checking for duplicates each time. Never a force-push.
5. **Progress** — while uploading, a notification shows "Backing up 3 of 12"
   with the current file, and the app's backup screen shows the same status.
   An open gallery shows newly committed items right away.

**GitHub limits:** GitHub allows about 80 content-creating requests a minute
and 500 an hour. A typical photo costs 4 (its chunk and three thumbnails) plus
its share of the commit's 3, so an initial backup of a large library proceeds
at roughly a hundred photos an hour. The app paces itself, and when GitHub says
to wait it pauses and resumes by itself (the status shows "waiting" with the
time it resumes).

### Settings

- **Wi-Fi only** — wait for an unmetered network (default on).
- **Only while charging** — wait until the phone is plugged in.
- **Folders** — all photos & videos, or camera only.
- **Back up now** / **Retry failed** — kick off a run or re-queue items that
  failed repeatedly.

Like Google Photos, backup never uses mobile data while roaming and pauses
while the battery is low.

## People matching in the background

The app finds faces in your photos on the phone (the web repo's
`docs/FACES.md`). While the app is open its own scanner does that; **after you
close it**, the same scanner keeps going in the background, so a big library
doesn't need the app left open for days. Settings › People has two switches:
**Find people in the background** (on by default) and **Only while charging**
(off by default).

- **When:** about 90 seconds after you close the app (or switch to another
  one), after backup uploads new photos while the app is closed, right after a
  run that ran out of time with photos left (about a minute later while
  charging, 20 minutes on battery), and about once an hour as a safety net.
  Always online (on Wi-Fi when backup is set to Wi-Fi only), never while the
  battery is low, and only once you're signed in with a library.
- **On battery** (unless "Only while charging" is on): runs last at most 8
  minutes and only go ahead with at least 30 % battery and Battery Saver off;
  while charging a run lasts up to 20 minutes. A quiet "Finding people in your
  photos" notification shows while it runs.
- **Stepping aside:** a run never starts while the app is on screen and stops
  within a second when you open it, turn it off, sign out, or the battery
  drops under 30 % unplugged; the page then gets up to 30 s to save what it
  found.
- **How:** the bundled web app's `runner/faces.html` runs in a private WebView
  of its own (`https://appassets.androidplatform.net`, no Capacitor, storage
  separate from the app's) that can only reach GitHub's API, on the
  WebAssembly backend. It commits to `manifest/faces.json` exactly like the
  app does.
- **Fewer downloads:** photos this phone backed up (or saved from the library)
  and hasn't changed since are read from the phone (`/_local/thumb/<mediaId>`,
  an 800 px WebP made on the phone) instead of downloading their thumbnails
  from GitHub.
- **Without the battery exemption** Android may refuse the notification; the
  run then goes on as a plain job and ends after 9 minutes (8 on battery).
- **Checking it:** Settings › People shows "Running now · 120 checked", "Last
  run 10:42 · 35 photos checked", or why the last run stopped or was skipped.
  `adb logcat -s 404gb.FaceScanWorker` logs every decision.

## Save to phone and share

From the gallery you can **save an original to the phone** (it appears in your
gallery under `Pictures/404GB` or `Movies/404GB`, with its original name) or
**share** it with any app. Both download the original from GitHub chunk by
chunk and verify its SHA-256 before the file is used; a saved item is recorded
as already backed up, so it's never uploaded again.

## Permissions and why

| Permission | Why |
| --- | --- |
| Photos and videos (`READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`; storage on Android 12 and older) | Find and read the items to back up. Nothing on the phone is modified or deleted. |
| Media location (`ACCESS_MEDIA_LOCATION`) | Upload originals with their GPS data instead of a location-stripped copy. |
| Notifications (Android 13+) | Upload progress and the "sign in" alert. Optional: backup works without it. |
| Battery optimization exemption | Asked once. Lets Android run backups promptly and show the progress notification while the app is in the background. |
| Storage write (Android 9 and older only) | Saving originals into Pictures/Movies. |
| Foreground service (data sync), internet | Running long uploads (and People matching) reliably. |
| Vibrate | Haptic feedback in the app (granted at install). |

**Android 14+ "Select photos and videos":** if you grant access to only some
items, backup can't see the rest. The status shows "needs permission"; turn
backup on again and choose **Allow all**.

## How the APK is built

GitHub Actions builds the APK in the cloud (`.github/workflows/android-apk.yml`)
on every push to `main`, `complete-app` and `standalone`:

1. Checks out the web app repo at the branch with **the same name as the branch
   being built**, or `main` when the web repo has no such branch.
2. Runs `npm ci && npm run build` there (Node 22) with
   `NEXT_PUBLIC_GITHUB_CLIENT_ID` taken from `config/github-client-id`, and
   copies its static export (`out/`) to this project's `www/` (build output,
   not committed).
3. `npx cap sync android`, then Gradle `assembleDebug`.
4. Uploads the APK as an artifact of the run, then runs the JVM unit tests.
   Only `main` builds (with passing tests) are published to the
   `android-latest` release.

If a build fails, each compiler error (and any failing unit test or web-build
error) is attached to the run as an annotation.

## Getting the APK (no Android Studio needed)

1. On your phone, open
   https://github.com/Manikandaprabu99/404gb_mobile/releases/download/android-latest/404gb.apk
2. Your browser will warn that the file might be harmful — normal for any app
   from outside the Play Store. Download it anyway.
3. Open the downloaded file. If Android asks, allow your browser (or Files app)
   to **Install unknown apps**, then tap **Install**.
4. Open 404GB, sign in (see above), pick your photo repo, and turn on backup.

To rebuild without changing code: repo → **Actions** → **Android APK** → **Run
workflow**.

**Updating:** install the new APK over the old one. Builds are signed with the
same debug key, kept in GitHub's Actions cache. GitHub drops caches unused for 7
days; if a build gets a new key, Android refuses the update ("App not
installed") — uninstall the old 404GB first, then install the new one, sign in
and turn backup back on.

**Coming from an older (server-based) build:** the old sign-in cookie isn't
used any more. Open the app once and sign in; backup carries on with what it
already backed up.

## Building locally (alternative)

Prerequisites: Android Studio (current stable), JDK 21, Node 22, Android SDK
Platform 36 (`compileSdk`/`targetSdk` 36, `minSdk` 24 — Android 7.0+).

1. Build the UI: in a checkout of the web repo,
   `NEXT_PUBLIC_GITHUB_CLIENT_ID=<your client id or empty> npm ci && npm run build`,
   then copy its `out/` folder to this project as `www/`.
2. Here: `npm install`, then `npx cap sync android`.
3. Android Studio → **File → Open → android**, let Gradle sync.
4. Run on a USB-connected phone, or **Build → Build APK(s)** (output in
   `android/app/build/outputs/apk/debug/`). `./gradlew testDebugUnitTest` runs
   the unit tests.

### App icon and splash

The launcher icon (adaptive + themed + legacy), splash icon and notification
icon are generated from `brand/mark.svg` by `npm run assets`
(`scripts/generate-brand-assets.mjs`, uses `sharp`). Commit the regenerated
files under `android/app/src/main/res`.

## Code map

`android/app/src/main/java/com/fourzerofourgb/app/backup/`

- `BackupPlugin` — the `Backup` Capacitor plugin used by the web app
  (`lib/native/backup.ts`): the GitHub session (`setAuth`/`getAuth`/`clearAuth`),
  enable/disable, options, status and `itemsUploaded` events, save/share, and the
  background People matching switch.
- `ScanWorker`, `BackupScheduler` — content-URI-triggered and periodic scans.
- `MediaScanner`, `BackupLedger` — MediaStore → SQLite ledger reconciliation.
- `UploadWorker`, `MediaUploader`, `Thumbnails` — the upload engine.
- `GitHubClient` — GitHub REST calls with retries, rate-limit handling and
  streamed base64 blob uploads.
- `LibraryWriter`, `LibraryIndex` — the race-safe commit (port of the web
  app's `lib/github/transaction.ts` and `commitMediaUpload`) and the cached
  `manifest/media-index.json`.
- `JsJson` — JSON read and written exactly like the web app's JavaScript, so
  manifest diffs stay one line per item (unit-tested in `src/test`).
- `MediaDownloader` — verified downloads for save-to-phone and share.
- `FaceScanWorker` — background People matching: runs the web app's
  `runner/faces.html` in a headless WebView (`setFaceScan`/`getFaceScanStatus`);
  `FaceScanPolicy` decides when a run may go ahead, how long, and the follow-up.
  `FaceRunnerHost` is the page's `window.FaceRunnerHost` bridge (session,
  limits, progress), `FaceRunnerAssets` serves the bundled export and this
  phone's thumbnails on the runner's own origin, and `AppVisibility` tells it
  when the app is on screen and queues a run when the app is closed.

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
- **Background People matching** shares the WebView renderer process with
  the app's own screen. If Android kills that renderer (out of memory) while
  the app is still in memory in the background, the whole app restarts; the
  next run carries on.
- **Debug build:** no auto-updates, and uninstalling clears local state
  (sign-in, backup settings, the ledger). Already backed-up photos are safe in
  GitHub; after reinstalling, items already in the library are recognised by
  hash and not uploaded again.
