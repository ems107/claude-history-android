# claude-history-android

An Android app that puts [claude-history](https://github.com/ems107/claude-history) on your phone: your servers in a list, each one opening in an embedded browser already signed in, and **the notifications that the web app cannot raise itself**.

That last part is the whole reason this exists. claude-history has a bell, tones and a narrator, but not a single system notification — `Notification` is secure-context only, so it works on `127.0.0.1` and not on `http://<lan-ip>:7433`, which is exactly the address remote access is for. This app is the piece that was missing.

> **Status: 0.1.0, being built.** The server list, the viewer, the notification service and the self-update are landing one at a time.

## What it does

- **N servers.** Each one is an alias, an ordered list of URLs and your credentials. If the first URL does not answer — you are on the tunnel instead of the LAN, say — it tries the next.
- **A viewer that is already signed in.** The app logs in natively, and hands the session cookie to the embedded browser, so you never see the login page. The interface is claude-history's own, unchanged: this version does not adapt it to a phone screen, so expect the desktop layout, small.
- **Notifications that mirror the bell.** Whatever `/api/notifications` says has stopped, your phone shows: a session that stops raises a notification, a session you attend anywhere makes it disappear. Tapping one opens that session in the viewer.
- **Its own updates.** The app checks its GitHub releases, verifies the APK's SHA-256 and installs it over itself.

## Installing

There is no Play Store listing. Download the APK from [releases](https://github.com/ems107/claude-history-android/releases) and open it; Android will ask you to allow installing from that source. Every later version can install itself from inside the app.

Because updates install over the app, **every release is signed with the same key**. An APK from anywhere else will not install on top of one from here.

### Play Protect will probably refuse it

On a phone with Google's services, expect *"App scan recommended"* and then *"Harmful app blocked"*. It is a false positive, and a predictable one: Play Protect has never seen this app, its signing key has no reputation, and it asks permission to install packages — which is exactly what a dropper does. Google's own scan of a release here answered *potentially unwanted*, with nothing behind it but those facts.

Sending the app to Google for scanning is what produces that verdict, so it does not help. What works: on the dialog, open **More details** and choose **Install without scanning**. It asks for your fingerprint or PIN, and then installs.

**It asks every time, not only on the first install** — measured across four releases here — so an update from inside the app hits the same dialog. That is why a blocked install now says so in those words, instead of reporting `INSTALL_FAILED_VERIFICATION_FAILURE` and leaving you to guess.

## Updating

Once a day the app makes **one small conditional request to `api.github.com`**, asking whether a newer release exists. That is the only thing this app does on the network by itself, it downloads nothing, and the switch that turns it off is in Settings.

When there is a new version, Settings offers it: the APK is downloaded, checked against the `checksums.txt` published beside it, and handed to Android — which asks you before replacing anything.

## What it asks of your phone, and why

| Permission | What breaks without it |
| --- | --- |
| Notifications | the whole point of the app |
| Run in the background without battery restrictions | the connection is dropped while the screen is off, so stops arrive late or not at all |
| Install unknown apps | only the self-update; you can always install the APK by hand instead |
| Start at boot | the app has to be opened by hand after every restart |

It keeps **one permanent low-priority notification** while it is watching. That is not a choice: Android only lets an app hold a connection in the background if it shows one.

## What it needs from the server

Remote access must be **on** in claude-history's settings, with a username and password set, and the port allowed through the Windows firewall — all three are explained in that project's own README. The app talks to a server exactly as a browser on the LAN does, over plain HTTP.

**If the phone cannot reach the server, no notification arrives.** There is no push service in the middle: no Google, no relay, no account. Off the LAN and off the tunnel, the app is quiet — and so is everything else, since you could not have opened the session either.
