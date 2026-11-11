# Nusuk Auto

Android app that does the repetitive part of the Nusuk Umrah challenge for you. It sits on top
of the official Nusuk app as a small floating widget, enters key codes one after another, and
plays the door quiz, while you watch or do something else.

- **Website and download:** <https://nusuk.minbars.net>
- **Community:** <https://www.facebook.com/groups/nusukauto/>
- **Developer:** [Jafran Hasan](https://jafran.dev)

> Nusuk Auto is an independent helper. It is not made by, or connected to, the Nusuk app or the
> Saudi Ministry of Hajj and Umrah. Use it at your own risk.

## What it does

| Mode | What happens |
|---|---|
| **Codes** | Types a code into Nusuk's "Your keys" page, taps Check, reads the result, collects the key, moves to the next code. Codes that were already tried are never tried again. |
| **Quiz** | Picks a door, reads the question and every option, taps the answer, reads whether Nusuk said right or wrong, then plays again. |

Other things in the app:

- **Floating widget** over Nusuk with start, stop and live progress. It closes itself when you
  leave Nusuk.
- **Human-like pace.** Before each quiz answer the app waits a random "thinking" time (2 to 5
  seconds by default, adjustable in Settings, or Fast for no wait).
- **Answers stored on the phone.** Known questions are answered with no network call. Unknown
  ones are asked from the server, and wrong answers are reported so nobody gets them again.
- **Add your own keys.** Paste any text that has keys in it; the app finds them and adds the new
  ones to the shared list.
- **Progress backup.** Tried codes are saved to the server against an anonymous device id, so a
  reinstall does not start from zero.
- **In-app updates**, checked with SHA-256 before install.
- **English and Bangla** interface, switchable in Settings.
- **Stats** for today and all time, **announcements** from the admin, and a built-in **Help** page.

## Permissions

| Permission | Why |
|---|---|
| Accessibility service | Read the Nusuk screen and tap on it. Limited to the Nusuk app. |
| Display over other apps | The floating widget. |
| Internet, network state | Code list, quiz answers, updates, announcements. |
| Install packages | Lets the app install its own update. |
| Notifications, run at startup | Announcements, checked about every 15 minutes. |

No location, contacts, phone, camera, microphone or storage access. No analytics or ad SDKs.
The device id is a SHA-256 hash of `ANDROID_ID`, never the raw value.

## Requirements for users

- Android 8.0 (API 26) or newer.
- The official Nusuk app, set to **English**. The app stops if Nusuk is in Arabic.
- Normal system font size works best. Large fonts are supported (the app scrolls to read
  everything), but are slower.

On Android 13 and newer, an app installed from an APK file has its Accessibility switch greyed
out at first. Open **Settings > Apps > Nusuk Auto > three dots (top right) > Allow restricted
settings**, then turn on Nusuk Auto in Accessibility.

## Build

There is no Gradle. The build is one shell script that calls the Android SDK tools directly:
`aapt2` (resources), `javac` (Java 17), `d8` (dex), `zipalign` and `apksigner`.

You need:

- macOS with Android command line tools from Homebrew, platform `android-35` and build tools
  `35.0.0`. The paths are at the top of `build.sh`; change them for another setup.
- JDK 17 or newer, and `d8`, `zip` and `adb` on your `PATH`.
- The release signing key (see below).

```bash
./build.sh
```

This writes `build/nusuk-auto.apk` and installs it with `adb` if a phone is connected.

### Signing key

`build.sh` expects two files next to it. Both are git-ignored and must never be committed:

- `signing.keystore`: the release key
- `signing.properties`: one line, `KS_PASS=<password>`

Every release must be signed with the same key. Android refuses an update signed with a
different one, and users would have to uninstall and lose their saved progress. The build
checks the certificate fingerprint and fails if it does not match.

To build your own copy for testing, create your own keystore and remove the fingerprint check
at the end of step 7 in `build.sh`. Such a build cannot update an official install.

## Release

1. Raise `android:versionCode` (by 1) and `android:versionName` in `AndroidManifest.xml`.
2. Run:

```bash
./publish.sh --notes "What changed" [--min N]
```

It builds, then uploads `nusuk-auto.apk`, `nusuk-auto-<code>.apk` (kept for rollback) and
`version.json` to the Cloudflare R2 bucket. The app reads `version.json` to learn about the
update. `--min N` forces an update on phones below versionCode `N`.

`publish.sh` uses the `wrangler` login from the web project and expects it at `../web`, so run
releases from the main project checkout, not from a standalone clone of this repo.

## Project layout

```
.
├── AndroidManifest.xml     package, version, permissions, components
├── build.sh                build + sign + install
├── publish.sh              build + upload a release
├── assets/
│   └── quiz_seed.json      verified quiz answers shipped with the app
├── res/                    layouts, drawables, fonts (IBM Plex, OFL), strings
└── src/com/nusuk/automator/
```

Main source files:

| File | Role |
|---|---|
| `NusukAccessibilityService.java` | The main loop. Reads the Nusuk screen and drives Codes and Quiz mode. |
| `OverlayService.java` | The floating widget. |
| `MainActivity.java` | Home screen: setup checks, mode cards, counters. |
| `KeyRepository.java` | Local list of codes and their results. |
| `CodeDownloader.java`, `CodeSync.java` | Pull the code list; back up tried codes. |
| `KeySubmit.java`, `AddKeysActivity.java` | "Add keys": find keys in pasted text and send them. |
| `QuizStore.java`, `QuizSync.java` | Answers kept on the phone, and syncing them from the server. |
| `QuizSolver.java`, `QuizBatch.java` | Ask the server for an answer; report results in batches. |
| `QuizPace.java` | The random thinking time. |
| `UpdateChecker.java`, `ApkInstaller.java`, `InstallReceiver.java` | In-app update. |
| `Notices.java`, `NoticeJob.java`, `NotificationsActivity.java` | Announcements. |
| `L.java` | English to Bangla translation of every text in the app. |
| `SettingsActivity.java`, `StatsActivity.java`, `TutorialActivity.java`, `DeveloperActivity.java` | The other screens. |

## Server

The app talks to the web app at `nusuk.minbars.net` (a Cloudflare Worker, kept in a separate
repository) for the code list, quiz answers, progress backup and announcements. Update files
are served from `files-nusuk.minbars.net`. Every request carries the anonymous device id in an
`X-Device-Id` header; there are no API keys or tokens in the app.

## About this repository

This repo is a mirror of the `android/` folder of the main Nusuk project, with its history.
Changes are made in the main project and pushed here with:

```bash
git subtree push --prefix=android android main
```
