# What this app needs from a claude-history server

**Load this before touching anything under `net/`.** It is the whole surface: six endpoints, one cookie and five traps. Everything here was read out of the server's source and measured against a live instance; the *reasoning* behind each rule lives in that project's `docs/AI_REMOTE_ACCESS.md` and is not repeated.

## Invariants

- **A remote request gets nothing until it signs in.** Only `/api/auth/*` and the static bundle answer first; everything else is 401 (remote access on) or 403 (off).
- **Every state-changing request must carry `Origin`.** GET does not. See the trap below — it is the one that bites a native client and no browser.
- **The bell is the server's, not ours.** We read it. We never dismiss.
- **Nothing this app calls may be local-only**: those answer 409 from another machine, by design, and the list of them is the server's `shared/src/localOnly.ts`.

## The endpoints

| Method | Path | What we use it for |
| --- | --- | --- |
| GET | `/api/auth/status` | four booleans — `remote`, `remoteAccessEnabled`, `configured`, `authenticated`. The only endpoint reachable without a session, so it is what "test this server" asks and what tells the difference between *wrong password*, *remote access is off* and *no credentials are set there* |
| POST | `/api/auth/login` | `{username, password}` → `Set-Cookie`. Backs off after failures (429 with `retryAfterSeconds`) |
| GET | `/api/notifications` | `{stopped: StoppedSessionEntry[]}`, newest first — the whole notification model |
| GET | `/api/live` | `LiveSessionEntry[]` — what is running over there this moment, which is what the cards and the permanent notice count |
| GET | `/api/events` | SSE. We care about two events, `{"type":"notifications-changed"}` and `{"type":"live-changed"}`; everything else is for the web UI |
| GET | `/api/settings` | the server's own notification preferences, which ours inherit: `notifyEnabled`, `notifyOnNeedsYou`, `notifyOnFinished`. **They arrive under a `settings` key**, beside a `paths` object — this is the only endpoint here whose payload is wrapped in something other than its own name, and reading it at the top level is a silent failure, not a loud one (see the fifth trap) |

`GET /api/meta` earns a seventh line for diagnostics alone: it carries the server's `version` and whether it is a dev instance. **Read it before concluding this app failed to parse something.** A field that arrives null on every row, or a shape that decodes into nothing, is a server too old to send it at least as often as it is a bug here — and the two look identical from the phone. The version is never worth writing down, only asking for: it moves whenever the user installs a release.

### `StoppedSessionEntry`

`sessionId`, `kind` (`needs-you` | `finished`), `waitingFor` (the CLI's own words — "permission prompt", "input needed"; null on `finished`), `at` (**epoch ms**, unlike the ISO strings elsewhere in that API), `source` (`cli` | `app`), plus `title`, `projectName`, `projectKey`, `cwd` and `stillOpen` for drawing the row without a second request.

`preview` is the quote: what the session said as it stopped, as `{kind, label, text, chars, truncated}`. `kind` is `tool` | `plan` | `question` | `answer` | `error` and decides what `label` holds — the tool's name, the plan's title, the question — with `label` null where the text is its own headline. `text` arrives cut to 600 characters, `chars` is the real length before the cut. **It is null far more often than it looks**, and the fourth trap is about why.

Everything a notification needs is in there. There is no second call.

### `LiveSessionEntry`

`sessionId`, `pid`, `cwd`, `entrypoint`, and the CLI's own `LiveInfo`: `status`, `waitingFor`, `name`, `startedAt`, `updatedAt`, `statusUpdatedAt`, `busySince`. A bare JSON array, not an object with a key. Dead pids are filtered out by the server, so a row here is a process that exists.

**`status` has four values and only four** — `busy`, `waiting`, `idle`, `shell` — read out of the CLI binary rather than guessed, plus `unknown`. `idle` and `shell` are one state: `shell` is `idle` with a shell open on top, and nothing about the conversation differs. It is a loose string on purpose, and so is ours: **a fifth value from a later CLI must count as nothing, never as idle.**

Two things this list is not. It is **not the bell**: a stop is a transition and this is a state, so every terminal somebody left open is in here, resting — which is exactly why the bell is kept server-side instead of derived from this. And it **includes the server's own `--print` runs**, which register a pid and report no status of their own; the server paints `busy`/`waiting` over the ones it started itself, and anything else is `unknown`.

## The five traps

### A POST with no `Origin` is refused, and only from another machine

The server's same-origin hook exempts GET and HEAD, then asks two questions of everything else: `Sec-Fetch-Site`, which a browser sets and we never will, and `Origin`, which must name the server itself. **No headers at all on a POST from off-machine is a refusal (403)** — the reasoning being that a caller with a session cookie and no browser is not a shape its own UI ever takes.

So **every POST this app makes carries `Origin: <scheme>://<host>:<port>` of the server it is talking to**, matching the `Host` header. Today that is the login and nothing else, and it is the first thing to check when a login answers 403 instead of 401.

### The cookie is a browser's, and we are not one

`HttpOnly`, `SameSite=Strict`, **no `Secure`** — that last one on purpose, because a `Secure` cookie would never be sent over the plain HTTP this whole thing runs on. It lasts **30 days** and holds no server-side state, so nothing is invalidated by the server restarting. Two things do invalidate it: rotating the secret ("sign out everywhere") and **renaming the user**.

An OkHttp `CookieJar` sees none of the browser semantics, which is what makes sharing it with the WebView possible: the native client logs in, and the cookie is handed to `CookieManager` before the first page load.

### A stop is a transition, and the list is memory

The bell only ever holds sessions seen to **leave** `busy` while that server process was running, and it holds them in RAM. **A server restart empties it.**

Four things withdraw a row on the server — visiting the session, dismissing it, clearing the bell, and the `claude` process going away — and all four reach us as the row simply not being in the next answer. **The row leaving is not the notification leaving.** The phone keeps it, marked read, until somebody takes it away: what the row's disappearance actually says is "this was attended", and that is worth telling rather than erasing. A server restart therefore marks every notification read at once, which is the honest reading of it — nobody can act on those rows any more — and is in any case the defect worth having, since the alternative is the phone quietly emptying itself.

The row itself is gone by the time this is noticed, so `notify/Notifications.kt` keeps the last one it drew from. Nothing is dismissed on the server for any of it, and this app still never calls `/api/notifications/dismiss`.

`sessionId` is the same key here and in `/api/live`, which is the whole of the app's `finished` count: a live session at rest whose row the bell is still holding has not been looked at. The server keeps the two apart on purpose — a stop is a transition, the live list is a state — so that crossing lives on the phone and is not to be asked of the API.

### A row still listed is re-read, not taken as unchanged

**The quote arrives on a second answer.** The server raises the row the instant the session stops — synchronously, so that `at` is honest — and reads the transcript a beat later, patching the row and emitting a **second** `notifications-changed`. That second row carries the same `sessionId` and the same `at`, and differs only in `preview`. It can also arrive minutes later: a session with no transcript yet is put on a retry list and filled in when one appears.

So **`at` says which stop it is, and nothing about what the row now says**. Deciding a listed row is a row already handled is how the web spent a while drawing every card without its quote, and it is the same mistake waiting here — with a second edge of its own, because redrawing an Android notification is only silent while the shade still holds it. `notify/Notifications.kt` has the whole rule.

### A decode that reads nothing succeeds

`GET /api/settings` answers `{"settings": {…}, "paths": {…}}`. Decoded at the top level — which is what it did until it was caught — the three preferences are simply not there, and **nothing says so**: `ignoreUnknownKeys` swallows `settings` and `paths`, every field on `ServerSettings` has a default, so the decode succeeds and reports everything on. `decode()` logs nothing, because nothing threw.

It stayed hidden for as long as it did because the server's own default is everything on, so the invented answer agreed with the real one until somebody actually switched a preference off over there. Then the phone's `Inherit` — the default, and what its edit screen recommends — silently meant `On`, forever.

The lesson is bigger than the one endpoint. **A default on every field plus `ignoreUnknownKeys` is a decoder that cannot fail**, and the two are on for good reasons: the first keeps an older server from throwing, the second keeps a newer one from throwing. Together they turn "I read the wrong shape" into "everything is at its default", and those are indistinguishable from the outside. So when a field seems stuck at its default, check the SHAPE before believing the value — `curl` the endpoint and compare it against the DTO, rather than reasoning about what should have been sent. `/api/meta` is the other half of the same habit.

Every other endpoint here is either wrapped in a key this app decodes (`{stopped: …}`) or a bare array. `/api/settings` is the only one wrapped in something else, and `SettingsEnvelope` exists to say so in the one place a reader will already be looking.

## Reaching the server at all

Plain HTTP, no TLS, on a LAN address or through a WireGuard tunnel. A server that is not reachable is not an error state to report loudly: it is Tuesday, and the app says so quietly and keeps retrying.

**The stream is silent, not idle.** `/api/events` sends nothing between events except `: hb` every 25 seconds, and that heartbeat is the only evidence the socket is still alive — a connection that dies without an RST looks exactly like a quiet afternoon. Reading it with no timeout is therefore a way to hang forever, believing you are connected.
