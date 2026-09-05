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
- **Watching is not one of those calls — it is what the app is for — and it is not gated by the notification switches.** Every configured server gets a held stream, plus a read of the bell and of what is live whenever that stream says either moved. Turning a server's notifications off makes it *quiet*, not *unwatched*: its counts are worth the same, and a preference flipped at the desk arrives on the next event. **Removing the server is what stops the app talking to it**, and the permanent notice says `· muted` on the line of any server that is being watched but will not speak.
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
| Failover | put a dead address first; the server must answer on the second, and a *wrong password* reply proves it got there |
| A stop arriving | open a session through the composer with `model: haiku`, let it finish, watch for `raised finished` in logcat |
| `needs-you` | ask that session to use `AskUserQuestion`, or to run a command it must ask about; the notification must land on the high-importance channel carrying the CLI's own `waitingFor` |
| The mirror, both ways | `POST /api/notifications/dismiss` on the server withdraws it from the phone; opening the session **from** the phone empties the bell. Measured: three dismissals on the server drew three `withdrew` lines within 70 ms |
| Swiped stays swiped | swipe one away, then make the server list it again — starting another session is enough. Measured: the bell listed **4 stopped** and the app raised only the new one |
| Counts, live | start a session on the server's machine: the card goes to `1 working` and the notice to the aggregate **with nobody touching the phone**. Provoke a permission → `1 waiting`. Close the terminal → it drops. `live on <server>` in logcat is the count that was believed. Measured on the DT50 against 1.20.0: `1 working · 1 idle` checked against `tasklist`, and `1 waiting, 1 working` while a composer session held a permission |
| Nothing open | with no `claude` anywhere, the card must show **no counts line at all** — not `0 idle`. Measured: a signed-in server with an empty `/api/live` draws the state line and nothing under it |
| A `--print` run | it registers a pid and reports no status, so it must count as none of the three. Measured: `live on Sobremesa: 1 working (4 not saying)` with four of them alive, and the visible numbers never moved |
| A muted server | still connects, still counts, still says `· muted`, and raises **nothing** |
| The quote | expand a `needs-you`: collapsed is still `Waiting for you — …`, expanded gains the tool and the command. Needs a server new enough to send `preview` — **1.20.0 has it, 1.19.2 does not**, and an app that draws no quote against an old server is right rather than broken. Measured: `Bash` over `git push origin main --force-with-lease` |
| The quote does not buzz twice | the delicate one: the quote arrives on a **second** event. logcat must read `raised` then `redrew`, and the phone must vibrate **once**. Measured twice, 0.4 s apart each time; the redraw carries `setOnlyAlertOnce`, which the system honours only while the shade still holds the notification — hence the guard below |
| The quote does not resurrect | swipe a notification within a second of it landing; the quote arriving after must not bring it back. **Not reproduced here** — the window measured 0.4 s and a hand cannot hit it. The guard is the shade's own list of what it is holding, and it is the reason a patch is skipped rather than posted |
| A cut quote | a long answer or plan: the last line must read `— cut at N of M characters`, and it must be VISIBLE — which is why the quote is cut again at 300 rather than drawn at the server's 600. Measured: `— cut at 300 of 445 characters`, on screen |
| A server with no quote | point the app at 1.19.2: the notification must look exactly as it did before, with no gaps or empty lines |
| **Doze** | `input keyevent 223`, `dumpsys battery unplug`, `dumpsys deviceidle force-idle`, then trigger a stop. `mWakefulness=Dozing`, `mHoldingDisplaySuspendBlocker=false`, `mCharging=false` and `mState=IDLE` must hold **before and after**. Always `unforce` and `battery reset` afterwards. Re-measured with every server watched and the live channel running: **4.4 s** from prompt to `raised`, and the quote 0.4 s behind it |
| Updating | cut two releases and let the app cross between them; expect Play Protect (see the README) |

Two things the DT50 cannot check, so do not waste time trying:

- **The old-WebView warning.** Its only valid provider is Chrome 138 — `cmd webviewupdate set-webview-implementation` refuses the system stub — so the banner's positive case has no device here.
- **Coming back after a reboot.** `persist.adb.tcp.port` is unset, so a reboot takes the adb connection with it.

## What is built, and what is not

0.1.3 has all four halves: the server list, the viewer, the notification service and the self-update. See the README for what a version actually does.
