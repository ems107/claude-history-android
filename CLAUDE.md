# CLAUDE.md

## What this is

An Android app that is a **client of [claude-history](https://github.com/ems107/claude-history)** and nothing else. It keeps a list of servers, opens each one in an embedded WebView already signed in, and — the reason it exists — raises the **system notifications that the web app structurally cannot**: `Notification` is secure-context only, so it works on `127.0.0.1` and not on the LAN address remote access is for.

Kotlin + Jetpack Compose, one module, no server side of its own. All four halves are in and have been used against real servers: the server list, the viewer, the notification service and the self-update.

**No version number appears anywhere in this file, on purpose.** What version this is, is `app/build.gradle.kts`; what a given version did is its release notes on GitHub; what the app does for a user is the README. A number written here would be a fourth answer, and the one nobody remembers to update.

## The other repository

`C:\Users\Edgar\Git\.claude-history` on this machine. **Read it, never write to it** — not the code, not the docs, not its dev instance, not its release. What this app needs from that API is written down once in [docs/AI_SERVER_CONTRACT.md](docs/AI_SERVER_CONTRACT.md); read that first, and go to the server's own `docs/` only for the reasoning behind it.

## Where the code is

One Gradle module, `app/`, one package, `io.github.ems107.claudehistory`, and **no dependency injection, no navigation library and no database**: each of those absences is a decision rather than a gap waiting to be filled.

| Where | What is in it |
| --- | --- |
| `ClaudeHistoryApp.kt` | the whole object graph, and it is four objects — the server store, the HTTP client, the reconciler, the watch state. They hang off the `Application` because the service and the screens must share every one of them: a second copy of any means a second server list, a second cookie jar, or a screen disagreeing with the shade about whether a server is up |
| `data/` | `Server` (alias, ordered URLs, credentials, three notification toggles), `ServerStore` (one JSON file, rewritten whole) and `Secrets`, which keeps each password as ciphertext under a hardware-backed key that never leaves the phone |
| `net/` | `ServerClient` and the DTOs it decodes. **Read [docs/AI_SERVER_CONTRACT.md](docs/AI_SERVER_CONTRACT.md) before touching either of them** |
| `notify/` | the reason the app exists. `WatchService` holds one job per server, `Reconciler` makes the shade match the bell, `WatchState` is what the service is seeing so that a screen can draw the same answer it does |
| `ui/` | four screens — list, edit, viewer, settings — and `MainActivity`, which holds one `Screen` value; back goes to the list, and the viewer's own handler wins so back walks the page history first |
| `update/` | the once-a-day GitHub check, the SHA-256 verification, and handing the APK to Android's package installer |

## Commands

| Command | What it does |
| --- | --- |
| `.\gradlew :app:assembleDebug` | debug APK in `app\build\outputs\apk\debug\` |
| `.\gradlew :app:assembleRelease` | **signed** release APK — needs `keystore.properties`, which is not in the repo |
| `adb -s <device> install -r <apk>` | install over the previous build |
| `adb -s <device> logcat -s claude-history` | the logs; there is nothing else to read |
| `adb -s <device> exec-out screencap -p > shot.png` | how the UI is checked — there is no emulator here |
| `.\scripts\release.ps1 -Version X.Y.Z -NotesFile <path>` | cut a release — **only when the user asks**. `-DryRun` builds and stops before the tag |

**Everything the app logs uses the single tag `claude-history`**, so that one filter is the whole instrument: `raised` / `redrew` / `withdrew` for a notification, `live on <server>` for a count that was believed, `bell for <server>` for what the server said and what preferences were in force. The verifying table below refers to those lines by name.

The wrapper needs a JDK and the SDK path comes from `local.properties`. Neither is in the repository; where they are **on this machine** is in `CLAUDE.local.md`, along with the phone and the servers to test against.

## Hard rules

- **The phone MIRRORS the bell; it never edits it.** What is shown is exactly what `GET /api/notifications` says: a row appears, a notification appears; the row goes, the notification goes. This app must **never** call `/api/notifications/dismiss` — dismissing is the act of the person who attended the session, and it reaches every device through the server on its own.
- **No push service, no account, no analytics.** The only hosts this app talks to are the servers the user typed in and `api.github.com` for its own updates. There is no Google in the middle, deliberately: a notification you cannot act on — because the phone cannot reach the server either — is worth less than the dependency costs.
- **Every automatic network call is switchable off and named in the README.** Today there is exactly one: the once-a-day update check, a conditional GET to `api.github.com` that downloads nothing. Adding another is allowed and costs precisely that — a switch, and a line in the README.
- **Watching is not one of those calls — it is what the app is for — and it is not gated by the notification switches.** Every configured server gets a held stream, plus a read of the bell and of what is live whenever that stream says either moved. Turning a server's notifications off makes it *quiet*, not *unwatched*: its counts are worth the same, and a preference flipped at the desk arrives on the next event. **Removing the server is what stops the app talking to it**, and the permanent notice says `· muted` on the line of any server that is being watched but will not speak.
- **Plain HTTP is deliberate, not an oversight.** claude-history has no HTTPS by design, so `network_security_config.xml` permits cleartext. Never "fix" it.
- **The signing key never enters the repository.** `keystore.properties` and the `.jks` are both gitignored, and both must be backed up: an update installs over the app only if it carries the same signature.
- **The debug build is signed with the RELEASE key wherever one is configured**, and that is not a convenience. Android refuses to install an APK whose signature differs from the installed one, so a debug build carrying the *debug* key can only be replaced by uninstalling first — which takes the configured servers and their passwords with it, because the key that encrypted them lives and dies with the install. A fresh clone with no `keystore.properties` still builds debug APKs; those are the ones that cannot be upgraded into.
- **NEVER cut a release on your own initiative.** Building and testing an APK is not publishing one. Tag and release only when the user asks for it in that turn.
- **The app is edge to edge on every device, and asks for it rather than waiting to be given it.** Android 15 imposes it on anything targeting API 35 or later and Android 16 removed the opt-out, so on a modern phone the window draws behind the status and navigation bars whatever the code says. `enableEdgeToEdge()` in `MainActivity` is what makes API 28 do the same — which is the only reason an inset bug is visible on the DT50 at all, instead of waiting on a Galaxy nobody here can attach a debugger to. So **every screen pads itself**: Material3 `Scaffold` and `TopAppBar` do it for the three list-shaped screens, and the viewer, whose bar is hand-built, does it by hand. Anything new that draws at an edge of the window is the same job again.
- **minSdk 28 is a real floor**, not a formality: the everyday test device is an Urovo DT50 on Android 9. Anything gated on a newer API must degrade there, never crash.
- **Ask for no permission the app does not need.** In particular there is no location permission, which is what reading the Wi-Fi SSID would cost — the URL failover remembers what worked instead of learning where it is.

## How work is routed

**A direct change goes straight on `main`; a change that needed a written plan gets its own branch, merged with `--no-ff` only after the user has used it; there are no pull requests.** The full rules — what counts as a plan, what the merge is allowed to carry, and the three gates that decide when a session may end — are in `CLAUDE.local.md`, which is not committed. That is the one place they live: do not restate them here.

## Verifying

There is no test suite. It is checked against a real server and a real phone — an Urovo DT50 on Android 9, reached over `adb connect`, with screenshots for anything visual.

**Almost every check below spends the subscription, so two rules come first.** They are the server repo's, in its `docs/AI_TESTING.md`, and they are repeated here because a `model: haiku` sitting in one cell of a table reads as a detail of that one check instead of as the rule it is.

- **`haiku` or `sonnet`, never `opus` or `fable`, and `low` effort.** Nothing here measures intelligence. It measures that a status arrives, that a notification is raised, and that a number moves — and a haiku session stopping proves all three exactly as well.
- **A terminal opened by hand does not obey that by itself**, which is how the rule gets broken with nothing failing: the CLI takes the machine's own default, which here is `opus[1m]` at `xhigh`. Pin it from inside before the first prompt — `/model haiku`, with the name as an argument so no picker opens — and check it where it cannot lie, in the transcript: every `assistant` line's `message.model` must read `claude-haiku-4-5-20251001`. Through the composer, ask for `model: haiku` on the request.
- **Never kill processes by filtering on `claude.exe`.** To bring a count down, close the terminal. A `taskkill /T /F` over a name takes out the agent running the test along with the target.

What has been proved, and how, so nothing is re-argued from memory:

| Check | How, and what it turned on |
| --- | --- |
| Signing in | the server's own log must say `failed login`, not `refused a cross-origin POST` — that is what proves the `Origin` header is going out |
| The viewer | the session list must draw **with its sidebar**. An empty page under a perfect header is the WebView `LayoutParams` bug, not a network one |
| The edges | nothing may sit under the status or navigation bar. The DT50 is a fair test of it, and only because the app asks for edge to edge there too: the viewer bar must start below the clock with its own surface painted behind it, the page must end above the navigation bar, and in landscape both must clear the bar wherever it went. Measured on the DT50 in portrait and landscape, light and dark — and dark mode is the second half of the check, because `uiMode` is a configuration this Activity handles itself and the status bar icons must still flip. What it cannot show is a gesture pill or a camera cutout |
| Failover | put a dead address first; the server must answer on the second, and a *wrong password* reply proves it got there |
| A stop arriving | open a session through the composer with `model: haiku`, let it finish, watch for `raised finished` in logcat |
| `needs-you` | ask that session to use `AskUserQuestion`, or to run a command it must ask about; the notification must land on the high-importance channel carrying the CLI's own `waitingFor` |
| The mirror, both ways | `POST /api/notifications/dismiss` on the server withdraws it from the phone; opening the session **from** the phone empties the bell. Measured: three dismissals on the server drew three `withdrew` lines within 70 ms |
| Swiped stays swiped | swipe one away, then make the server list it again — starting another session is enough. Measured: the bell listed **4 stopped** and the app raised only the new one |
| Counts, live | start a session on the server's machine: the card goes to `1 working` and the notice to the aggregate **with nobody touching the phone**. Provoke a permission → `1 waiting`. Close the terminal → it drops. `live on <server>` in logcat is the count that was believed. Measured on the DT50 against 1.20.0: `1 working · 1 idle` checked against `tasklist`, and `1 waiting, 1 working` while a composer session held a permission |
| Nothing open | with no `claude` anywhere, the card must show **no counts line at all** — not `0 idle`. Measured: a signed-in server with an empty `/api/live` draws the state line and nothing under it |
| A `--print` run | it registers a pid and reports no status, so it must count as none of the three. Measured: `live on Sobremesa: 1 working (4 not saying)` with four of them alive, and the visible numbers never moved |
| A muted server | still connects, still counts, says so, and raises **nothing**. Measured: `notify=EffectiveNotify(enabled=false, …)` beside `live on Sobremesa: 1 working`, and the card reading `Signed in · notifications off`. Turn it back and the label must go **at once** — if it lingers, the job was not restarted |
| The quote | expand a `needs-you`: collapsed is still `Waiting for you — …`, expanded gains the tool and the command. Needs a server new enough to send `preview` — **1.20.0 has it, 1.19.2 does not**, and an app that draws no quote against an old server is right rather than broken. Measured: `Bash` over `git push origin main --force-with-lease` |
| The quote does not buzz twice | the delicate one: the quote arrives on a **second** event. logcat must read `raised` then `redrew`, and the phone must vibrate **once**. Measured twice, 0.4 s apart each time; the redraw carries `setOnlyAlertOnce`, which the system honours only while the shade still holds the notification — hence the guard below |
| The quote does not resurrect | swipe a notification within a second of it landing; the quote arriving after must not bring it back. **Not reproduced here** — the window measured 0.4 s and a hand cannot hit it. The guard is the shade's own list of what it is holding, and it is the reason a patch is skipped rather than posted |
| A cut quote | a long answer or plan: the last line must read `— cut at N of M characters`, and it must be VISIBLE — which is why the quote is cut again at 300 rather than drawn at the server's 600. Measured: `— cut at 300 of 445 characters`, on screen |
| A server with no quote | the fallback must look exactly as a notification did before quotes existed, with no gaps and no empty lines. Mostly free: every `raised` that is later followed by `redrew` WAS the no-quote drawing, because the server only fills a `preview` that is still null — so the fallback runs on the first post of every single stop. Pointing the app at a server too old to send the field is the deliberate version of the check, and it needs one that still exists to point at |
| **Doze** | `input keyevent 223`, `dumpsys battery unplug`, `dumpsys deviceidle force-idle`, then trigger a stop. `mWakefulness=Dozing`, `mHoldingDisplaySuspendBlocker=false`, `mCharging=false` and `mState=IDLE` must hold **before and after**. Always `unforce` and `battery reset` afterwards. Re-measured with every server watched and the live channel running: **4.4 s** from prompt to `raised`, and the quote 0.4 s behind it |
| Updating | cut two releases and let the app cross between them; expect Play Protect (see the README) |

Two things the DT50 cannot check, so do not waste time trying:

- **The old-WebView warning.** Its only valid provider is Chrome 138 — `cmd webviewupdate set-webview-implementation` refuses the system stub — so the banner's positive case has no device here.
- **Coming back after a reboot.** `persist.adb.tcp.port` is unset, so a reboot takes the adb connection with it.
