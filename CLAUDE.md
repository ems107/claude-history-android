# CLAUDE.md

## What this is

An Android app that is a **client of [claude-history](https://github.com/ems107/claude-history)** and nothing else. It keeps a list of servers, opens each one in an embedded WebView already signed in, and — the reason it exists — raises the **system notifications that the web app structurally cannot**: `Notification` is secure-context only, so it works on `127.0.0.1` and not on the LAN address remote access is for.

Kotlin + Jetpack Compose, one module, no server side of its own.

## The other repository

`C:\Users\Edgar\Git\.claude-history` on this machine. **Read it, never write to it** — not the code, not the docs, not its dev instance on 7434, not its release on 7433. What this app needs from that API is written down once in [docs/AI_SERVER_CONTRACT.md](docs/AI_SERVER_CONTRACT.md); read that first, and go to the server's own `docs/` only for the reasoning behind it.

## Commands

The wrapper needs a JDK; `JAVA_HOME` is set for the user on this machine to the JDK 21 that came with Visual Studio's .NET Android workload.

| Command | What it does |
| --- | --- |
| `.\gradlew :app:assembleDebug` | debug APK in `app\build\outputs\apk\debug\` |
| `.\gradlew :app:assembleRelease` | **signed** release APK — needs `keystore.properties`, which is not in the repo |
| `adb -s <device> install -r <apk>` | install over the previous build |
| `adb -s <device> logcat` | the logs; there is nothing else to read |
| `adb -s <device> exec-out screencap -p > shot.png` | how the UI is checked — there is no emulator here |
| `.\scripts\release.ps1 -Version X.Y.Z -NotesFile <path>` | cut a release — **only when the user asks**. `-DryRun` builds and stops before the tag |

`local.properties` points at a **user-owned SDK** in `%LOCALAPPDATA%\Android\Sdk`, not at the one Visual Studio installed under `Program Files`: that one cannot be added to without elevation and has no accepted licence files. Its `platform-tools` is junctioned into ours, so there is exactly one `adb` on the machine and no version fight with Visual Studio.

## Hard rules

- **The phone MIRRORS the bell; it never edits it.** What is shown is exactly what `GET /api/notifications` says: a row appears, a notification appears; the row goes, the notification goes. This app must **never** call `/api/notifications/dismiss` — dismissing is the act of the person who attended the session, and it reaches every device through the server on its own.
- **No push service, no account, no analytics.** The only hosts this app talks to are the servers the user typed in and `api.github.com` for its own updates. There is no Google in the middle, deliberately: a notification you cannot act on — because the phone cannot reach the server either — is worth less than the dependency costs.
- **Every automatic network call is switchable off and named in the README.** Today there is exactly one: the once-a-day update check, a conditional GET to `api.github.com` that downloads nothing. Adding another is allowed and costs precisely that — a switch, and a line in the README.
- **Plain HTTP is deliberate, not an oversight.** claude-history has no HTTPS by design, so `network_security_config.xml` permits cleartext. Never "fix" it.
- **The signing key never enters the repository.** `keystore.properties` and the `.jks` are both gitignored, and both must be backed up: an update installs over the app only if it carries the same signature.
- **NEVER cut a release on your own initiative.** Building and testing an APK is not publishing one. Tag and release only when the user asks for it in that turn.
- **minSdk 28 is a real floor**, not a formality: the everyday test device is an Urovo DT50 on Android 9. Anything gated on a newer API must degrade there, never crash.
- **Ask for no permission the app does not need.** In particular there is no location permission, which is what reading the Wi-Fi SSID would cost — the URL failover remembers what worked instead of learning where it is.

## Planning discipline

Same as the server repo, and for the same reasons:

- **A direct change goes straight on `main`** — anything that needed no written plan.
- **A written plan gets its own branch**, named after what it implements, merged with `git merge --no-ff` and a subject that names the thing rather than the branch. **Only the user closes a plan**, after using it.
- **No pull requests.** Nobody else reviews this repo.

## What is built, and what is not

0.1.0 is the scaffold: it compiles, installs and shows its own version. The server list, the viewer, the notification service and the self-update are landing one at a time — see the README for what a version actually does.
