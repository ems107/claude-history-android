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
| `data/` | `Server` (alias, ordered URLs, credentials, an on/off switch, three notification toggles), `ServerStore` (one JSON file, rewritten whole) and `Secrets`, which keeps each password as ciphertext under a hardware-backed key that never leaves the phone |
| `net/` | `ServerClient` and the DTOs it decodes. **Read [docs/AI_SERVER_CONTRACT.md](docs/AI_SERVER_CONTRACT.md) before touching either of them** |
| `notify/` | the reason the app exists. `WatchService` holds one job per server, `Reconciler` makes the shade match the bell, `WatchState` is what the service is seeing so that a screen can draw the same answer it does |
| `ui/` | four screens — list, edit, viewer, settings — and `MainActivity`, which holds one `Screen` value; back goes to the list, and the viewer's own handler wins so back walks the page history first. The list reorders by dragging; the viewer's bar owns the page zoom and the desktop switch, and leaving it throws the page away |
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

**Everything the app logs uses the single tag `claude-history`**, so that one filter is the whole instrument: `raised` / `redrew` / `read` / `withdrew` for a notification, `live on <server>` for a count that was believed, `bell for <server>` for what the server said and what preferences were in force. The verifying table below refers to those lines by name.

The wrapper needs a JDK and the SDK path comes from `local.properties`. Neither is in the repository; where they are **on this machine** is in `CLAUDE.local.md`, along with the phone and the servers to test against.

## Hard rules

- **The phone MIRRORS the bell, and marks what it outlives; it never edits it.** A row appears, a notification appears. A row **goes** and the notification does not: it stays, with `✓ Read ·` in front of its state, until a finger takes it away — because a notification that withdraws itself is a phone that buzzed and has nothing to show for it by the time you look. Two things still remove one outright: attending it here (tapping or swiping, which is you having seen it), and a preference (muting the server, or switching off one of its two kinds, which is you asking not to see these at all). This app must **never** call `/api/notifications/dismiss` — dismissing is the act of the person who attended the session, and it reaches every device through the server on its own. The mark is drawn on the phone and nowhere else.
- **`finished` is a fourth count that exists only here.** The CLI calls a session that just ended its turn and a terminal left open on Tuesday the same `idle`, and only one of them is asking for anything. The bell is the difference, so the cards and the notice read `[waiting, working, finished, idle]`, with `finished` **carved out of** `idle` rather than added beside it — the totals and the `(N not saying)` residue are unchanged. Read anywhere, it goes back to being idle on its own. The crossing happens where the counts are READ (`ServerLive.counts`), not where they are written, because the bell and the live list arrive on separate coroutines 400 ms apart; do not move it.
- **No push service, no account, no analytics.** The only hosts this app talks to are the servers the user typed in and `api.github.com` for its own updates. There is no Google in the middle, deliberately: a notification you cannot act on — because the phone cannot reach the server either — is worth less than the dependency costs.
- **Every automatic network call is switchable off and named in the README.** Today there is exactly one: the once-a-day update check, a conditional GET to `api.github.com` that downloads nothing. Adding another is allowed and costs precisely that — a switch, and a line in the README.
- **Watching is not one of those calls — it is what the app is for — and it is not gated by the notification switches.** Every ENABLED server gets a held stream, plus a read of the bell and of what is live whenever that stream says either moved. Turning a server's notifications off makes it *quiet*, not *unwatched*: its counts are worth the same, and a preference flipped at the desk arrives on the next event. The permanent notice says `· muted` on the line of any server that is being watched but will not speak.
- **A server has three states, not two, and the third is `enabled`.** Switched off in its own settings it is not connected to, not counted, not a line on the notice and not openable — the app behaves as though it were not there, while keeping the address and the password that were expensive to type. So **switching a server off, or removing it, is what stops the app talking to it**; muting never does. It costs no branch in `WatchService`, and that is the point: the two collectors see `servers.watched()`, so a server going off reaches `syncJobs` as absent and takes the path a deleted one takes.
- **Plain HTTP is deliberate, not an oversight.** claude-history has no HTTPS by design, so `network_security_config.xml` permits cleartext. Never "fix" it.
- **The signing key never enters the repository.** `keystore.properties` and the `.jks` are both gitignored, and both must be backed up: an update installs over the app only if it carries the same signature.
- **The debug build is signed with the RELEASE key wherever one is configured**, and that is not a convenience. Android refuses to install an APK whose signature differs from the installed one, so a debug build carrying the *debug* key can only be replaced by uninstalling first — which takes the configured servers and their passwords with it, because the key that encrypted them lives and dies with the install. A fresh clone with no `keystore.properties` still builds debug APKs; those are the ones that cannot be upgraded into.
- **NEVER cut a release on your own initiative.** Building and testing an APK is not publishing one. Tag and release only when the user asks for it in that turn.
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
| The viewer | the session list must draw, and draw **whole** — its own content, not a strip. An empty page under a perfect header is the WebView `LayoutParams` bug, not a network one. Which layout appears is no longer the tell: the viewer now asks for the page at the phone's width, so a server new enough gives its phone layout and an older one gives a desktop layout too wide to fit. Both are correct; the sidebar comes back with the desktop button or a low enough zoom |
| The edges | nothing may sit under the status or navigation bar. The DT50 is a fair test of it, and only because the app asks for edge to edge there too: the viewer bar must start below the clock with its own surface painted behind it, the page must end above the navigation bar, and in landscape both must clear the bar wherever it went. Measured on the DT50 in portrait and landscape, light and dark — and dark mode is the second half of the check, because `uiMode` is a configuration this Activity handles itself and the status bar icons must still flip. What it cannot show is a gesture pill or a camera cutout |
| Failover | put a dead address first; the server must answer on the second, and a *wrong password* reply proves it got there |
| A stop arriving | open a session through the composer with `model: haiku`, let it finish, watch for `raised finished` in logcat |
| `needs-you` | ask that session to use `AskUserQuestion`, or to run a command it must ask about; the notification must land on the high-importance channel carrying the CLI's own `waitingFor` |
| The mirror, both ways | `POST /api/notifications/dismiss` on the server must leave the notification **in place**, marked `✓ Read · Finished`, with one `read` line in logcat and **no** buzz; opening the session **from** the phone empties the bell. Measured on the DT50 against 1.21.0, and again the ordinary way — a session going busy again withdraws its own row, and the phone marked it read |
| Read is not a preference | the mark is for a row the BELL let go. Mute the server with a read notification sitting there and it must be **withdrawn**, not left marked — muting has always cleared the shade. Measured: `withdrew` on the read one the moment `notify=EffectiveNotify(enabled=false, …)` arrived |
| A new stop on a read one | the delicate one, and the reason `onScreen()` carries each notification's `when`. Make the same session stop again while its read notification is still on the shade: it must be **`raised`**, not `redrew`, and lose the mark. A leftover on the shade is no longer proof the process restarted; only the same `at` is. Measured |
| Tapping leaves nothing | tap an **unread** one: the viewer opens on that session, the notification goes, and no read copy is left behind. Measured, with the bell emptying as the session was visited |
| Swiped stays swiped | swipe one away, then make the server list it again — starting another session is enough. Measured: the bell listed **4 stopped** and the app raised only the new one |
| Counts, live | start a session on the server's machine: the card goes to `1 working` and the notice to the aggregate **with nobody touching the phone**. Provoke a permission → `1 waiting`. Close the terminal → it drops. `live on <server>` in logcat is the count that was believed. Measured on the DT50 against 1.20.0: `1 working · 1 idle` checked against `tasklist`, and `1 waiting, 1 working` while a composer session held a permission |
| `finished` against `idle` | let a session end its turn and stay open: it must read `1 finished`, in green, not `1 idle` — on the card and in the notice. Attend it anywhere and it must fall back to `1 idle` **at once**, without waiting for the next live read, because the cross happens where the counts are read. Measured on the DT50 against 1.21.0, both themes, with `status: "shell"` counting as stopped |
| and nothing else moves | a composer run reports `unknown` and must count as none of the four **even while it is in the bell** — the one case where a naive "in the bell means finished" would be wrong. Measured: `live on Sobremesa: 1 working (1 not saying)` with the bell holding that very session |
| Nothing open | with no `claude` anywhere, the card must show **no counts line at all** — not `0 idle`. Measured: a signed-in server with an empty `/api/live` draws the state line and nothing under it |
| A `--print` run | it registers a pid and reports no status, so it must count as none of the three. Measured: `live on Sobremesa: 1 working (4 not saying)` with four of them alive, and the visible numbers never moved |
| A disabled server | switch it off in its own settings: the card must read `Disabled` and stop answering the touch, its `bell for` and `live on` lines must stop, and it must **leave** the permanent notice rather than appear muted on it. Any notification of its own goes with it. Switch it back on and it must reconnect **without the password being typed again**, which is the whole point of not deleting it. Measured on the DT50: the notice went to `Sobremesa — 1 working, 3 idle` with nothing about the other one, and re-enabling reconnected in under a second |
| Every server off | the service must stop and the permanent notice go, the same branch an empty list takes |
| Reordering | long-press a card, drag it past another, let go. The order must survive leaving the screen and must survive killing the app — it is in `servers.json`, not in memory — and the notice must list in the new order. **logcat must stay silent**: reordering restarts no watch job. Do one with a counts line showing, because the cards are then different heights and that is what the offset correction is for |
| A muted server | still connects, still counts, says so, and raises **nothing**. Measured: `notify=EffectiveNotify(enabled=false, …)` beside `live on Sobremesa: 1 working`, and the card reading `Signed in · notifications off`. Turn it back and the label must go **at once** — if it lingers, the job was not restarted |
| The quote | expand a `needs-you`: collapsed is still `Waiting for you — …`, expanded gains the tool and the command. Needs a server new enough to send `preview` — **1.20.0 has it, 1.19.2 does not**, and an app that draws no quote against an old server is right rather than broken. Measured: `Bash` over `git push origin main --force-with-lease` |
| The quote does not buzz twice | the delicate one: the quote arrives on a **second** event. logcat must read `raised` then `redrew`, and the phone must vibrate **once**. Measured twice, 0.4 s apart each time; the redraw carries `setOnlyAlertOnce`, which the system honours only while the shade still holds the notification — hence the guard below |
| The quote does not resurrect | swipe a notification within a second of it landing; the quote arriving after must not bring it back. **Not reproduced here** — the window measured 0.4 s and a hand cannot hit it. The guard is the shade's own list of what it is holding, and it is the reason a patch is skipped rather than posted |
| A cut quote | a long answer or plan: the last line must read `— cut at N of M characters`, and it must be VISIBLE — which is why the quote is cut again at 300 rather than drawn at the server's 600. Measured: `— cut at 300 of 445 characters`, on screen |
| A server with no quote | the fallback must look exactly as a notification did before quotes existed, with no gaps and no empty lines. Mostly free: every `raised` that is later followed by `redrew` WAS the no-quote drawing, because the server only fills a `preview` that is still null — so the fallback runs on the first post of every single stop. Pointing the app at a server too old to send the field is the deliberate version of the check, and it needs one that still exists to point at |
| Page zoom | 100 on entry; `−` down to 30 and `+` up to 300, each greying out at its end. **The check is that it RE-LAYS-OUT**, not that it magnifies: at 50 % more page must fit across, at 200 % everything must be twice the size, and at **no** setting may the page scroll sideways. A region growing under a fixed layout is a pinch, and the bar is not the pinch. **Both modes, because there is only one rule**: the switch picks the width at 100 and the zoom divides it. Measured on the DT50 at 150 % and 200 % each way against 1.21.0, and at 50 % against 1.20.0 |
| Zoom survives a reload | set 150, press reload: it must come back at 150. `onPageFinished` re-injects, and if it does not the page returns to 100 with the bar still claiming otherwise |
| Desktop mode | **off** on entry, every time. On, the 1280 px layout scaled to fit, and the button must look pressed while it is. Leave and come back: off again. Zooming **in** from there narrows the layout like anywhere else, and 1280 divided by 1.7 is 753, so the step from 160 to 170 crosses claude-history's own 768 px breakpoint and the phone layout comes back, larger. That is reflowing working, not failing — reading the wide layout closer is the pinch's job |
| The pinch | still there, and it is the other half: two fingers must magnify whatever is on screen, leave the layout alone, and leave the bar's percentage alone — that number is the layout zoom and nothing else. It needs two real fingers; `adb` cannot inject a second pointer on this device, so this row has never been driven from a script |
| Servers starts from zero | open a session, press **Servers**, open the same server again — the session list, not the session, and the zoom back at 100 with desktop off. The phone's back button walking all the way out must do exactly the same, because it is the same act |
| Back still walks the page | inside a session, back must reach the session list before it leaves the viewer |
| The progress line | reload: a line must run under the bar and disappear. It is the only thing that fills the white gap while a fresh WebView loads |
| **Doze** | `input keyevent 223`, `dumpsys battery unplug`, `dumpsys deviceidle force-idle`, then trigger a stop. `mWakefulness=Dozing`, `mHoldingDisplaySuspendBlocker=false`, `mCharging=false` and `mState=IDLE` must hold **before and after**. Always `unforce` and `battery reset` afterwards. Re-measured with every server watched and the live channel running: **4.4 s** from prompt to `raised`, and the quote 0.4 s behind it |
| Updating | cut two releases and let the app cross between them; expect Play Protect (see the README) |

Three things a script cannot check here, so do not waste time trying:

- **The old-WebView warning.** Its only valid provider is Chrome 138 — `cmd webviewupdate set-webview-implementation` refuses the system stub — so the banner's positive case has no device here.
- **Coming back after a reboot.** `persist.adb.tcp.port` is unset, so a reboot takes the adb connection with it.
- **The pinch.** `input` drives one pointer and this device's `sendevent` multitouch is not worth the fragility, so the two-finger gesture is checked by hand or not at all. A double tap is not a substitute: the layout always fits exactly, so there is nothing for it to zoom to and it does nothing either way.
