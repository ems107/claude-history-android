# What this app needs from a claude-history server

**Load this before touching anything under `net/`.** It is the whole surface: five endpoints, one cookie and three traps. Everything here was read out of the server's source and measured against a live 1.19.0 instance; the *reasoning* behind each rule lives in that project's `docs/AI_REMOTE_ACCESS.md` and is not repeated.

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
| GET | `/api/events` | SSE. We care about one event, `{"type":"notifications-changed"}`; everything else is for the web UI |
| GET | `/api/settings` | the server's own notification preferences, which ours inherit: `notifyEnabled`, `notifyOnNeedsYou`, `notifyOnFinished` |

`GET /api/meta` is worth a sixth line for diagnostics alone: it carries the server's `version` and whether it is a dev instance.

### `StoppedSessionEntry`

`sessionId`, `kind` (`needs-you` | `finished`), `waitingFor` (the CLI's own words — "permission prompt", "input needed"; null on `finished`), `at` (**epoch ms**, unlike the ISO strings elsewhere in that API), `source` (`cli` | `app`), plus `title`, `projectName`, `projectKey`, `cwd` and `stillOpen` for drawing the row without a second request.

Everything a notification needs is in there. There is no second call.

## The three traps

### A POST with no `Origin` is refused, and only from another machine

The server's same-origin hook exempts GET and HEAD, then asks two questions of everything else: `Sec-Fetch-Site`, which a browser sets and we never will, and `Origin`, which must name the server itself. **No headers at all on a POST from off-machine is a refusal (403)** — the reasoning being that a caller with a session cookie and no browser is not a shape its own UI ever takes.

So **every POST this app makes carries `Origin: <scheme>://<host>:<port>` of the server it is talking to**, matching the `Host` header. Today that is the login and nothing else, and it is the first thing to check when a login answers 403 instead of 401.

### The cookie is a browser's, and we are not one

`HttpOnly`, `SameSite=Strict`, **no `Secure`** — that last one on purpose, because a `Secure` cookie would never be sent over the plain HTTP this whole thing runs on. It lasts **30 days** and holds no server-side state, so nothing is invalidated by the server restarting. Two things do invalidate it: rotating the secret ("sign out everywhere") and **renaming the user**.

An OkHttp `CookieJar` sees none of the browser semantics, which is what makes sharing it with the WebView possible: the native client logs in, and the cookie is handed to `CookieManager` before the first page load.

### A stop is a transition, and the list is memory

The bell only ever holds sessions seen to **leave** `busy` while that server process was running, and it holds them in RAM. **A server restart empties it**, and our notifications empty with it. That is not a bug to work around: it is what "mirror" means, and a phone that kept showing a stop the server has forgotten would be showing something nobody can act on.

Four things withdraw a row on the server — visiting the session, dismissing it, clearing the bell, and the `claude` process going away — and all four reach us as the row simply not being in the next answer.

## Reaching the server at all

Plain HTTP, no TLS, on a LAN address or through a WireGuard tunnel. A server that is not reachable is not an error state to report loudly: it is Tuesday, and the app says so quietly and keeps retrying.
