package io.github.ems107.claudehistory.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ems107.claudehistory.ClaudeHistoryApp
import io.github.ems107.claudehistory.MainActivity
import io.github.ems107.claudehistory.R
import io.github.ems107.claudehistory.data.Server
import io.github.ems107.claudehistory.data.watched
import io.github.ems107.claudehistory.net.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The service that makes this app worth installing: it holds a connection to
 * every server you configured, mirrors their bells onto the phone, and keeps a
 * running count of what is alive on each of them.
 *
 * **It runs in the foreground, with a permanent notice, because that is the only
 * way Android lets an app keep a socket open while the screen is off.** There is
 * no push service in the middle -- no Google, no relay, no account -- which
 * means it only works while the phone can reach the server. That is a real
 * limit, and it is a smaller one than it looks: a notification you could not act
 * on, because the viewer would not load either, is worth very little.
 *
 * **Losing a server does NOT clear its notifications.** The phone mirrors what
 * the bell says, and an unreachable server has not said anything -- the last
 * thing it did say still stands. Clearing on a dropped connection would mean a
 * blink of the Wi-Fi silently swallowing the one alert you were waiting for.
 *
 * **The counts are the opposite, and for the same reason.** A notification is a
 * record of something that happened; a count is a claim about this moment. So a
 * server that stops answering keeps its notifications and loses its numbers --
 * "2 waiting" about a machine that has been off the network for an hour is the
 * kind of true-an-hour-ago that gets somebody to walk to a desk for nothing.
 *
 * **Every ENABLED server is watched, a muted one included.** Notification
 * preferences decide whether a stop is ANNOUNCED, not whether the server is
 * looked at: its counts are worth the same either way, and a preference changed
 * at the desk now arrives on the next event instead of within ten minutes.
 *
 * **Disabling a server is the other thing, and it is not a louder mute.** A
 * server switched off in its own settings never reaches this service at all: it
 * is not connected to, not counted, and not a line on the permanent notice. The
 * two states are told apart on purpose -- `· muted` says something is being
 * watched for you and will not speak, and a disabled server is not being watched
 * for you at all.
 */
class WatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private val jobs = mutableMapOf<String, Job>()

    /** What each running job was started with, so an edit can restart it. */
    private val started = mutableMapOf<String, Server>()

    /**
     * Both the server list arriving and a network appearing rewrite [jobs], from
     * different coroutines on a multi-threaded dispatcher. Without this they can
     * interleave into a server watched twice, or not at all.
     */
    private val jobsLock = Mutex()

    private val graph get() = application as ClaudeHistoryApp
    private val reconciler get() = graph.reconciler

    /** It never varies, and the notice is redrawn often enough to care. */
    private val openApp: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // A network arriving is the one moment a backoff is certainly wrong:
            // start every server again instead of waiting out a five-minute wait
            // that was earned on a different network.
            scope.launch { restartAll() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundNotice()

        // Only the servers that are switched on: a disabled one has to reach
        // [syncJobs] as ABSENT, so that it takes the same path a deleted one
        // does -- job cancelled, notifications withdrawn, card state dropped.
        scope.launch {
            graph.store.servers.map { it.watched() }
                .distinctUntilChanged()
                .collect { servers -> syncJobs(servers) }
        }

        // The notice follows what the two of them SAY, not every time they
        // change: `live-changed` fires on fields that move without moving a
        // count -- a turn's clock, a session's name -- and each redraw is a
        // binder round-trip and a re-inflation in the shade.
        scope.launch {
            combine(graph.store.servers.map { it.watched() }, graph.watch.servers) { servers, live ->
                notice(servers, live)
            }
                .distinctUntilChanged()
                .conflate()
                .collect { show(it) }
        }

        runCatching {
            getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                networkCallback,
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        }
        scope.cancel()
        // What is in there is a claim about right now, and there is about to be
        // nobody making it.
        graph.watch.clear()
        // The notice goes with it. Stopping the service normally takes it away,
        // but the coroutine that redraws it is its own: it can perfectly well
        // post one on the way out, and an ongoing notification is not something
        // the person left looking at it can swipe off.
        runCatching { NotificationManagerCompat.from(this).cancel(Notifications.WATCHING_ID) }
        super.onDestroy()
    }

    private suspend fun syncJobs(servers: List<Server>) = jobsLock.withLock {
        val ids = servers.map { it.id }.toSet()
        jobs.keys.filter { it !in ids }.forEach { gone ->
            jobs.remove(gone)?.cancelAndJoin()
            started.remove(gone)
            reconciler.forget(gone)
            graph.watch.forget(gone)
        }
        servers.forEach { server ->
            // Edited while it was being watched. A job carries the address, the
            // credentials and the preferences it STARTED with, so a corrected
            // password used to sit unused until the connection happened to drop,
            // and a server muted here went on looking unmuted until the next
            // thing happened over there.
            if (jobs[server.id] != null && !sameWatch(started[server.id], server)) {
                jobs.remove(server.id)?.cancelAndJoin()
            }
            // `isActive`, not merely present: `watch` returns the moment the
            // store no longer knows the id, and a finished job left in the map
            // looks exactly like a running one.
            if (jobs[server.id]?.isActive != true) {
                started[server.id] = server
                jobs[server.id] = watch(server.id)
            }
        }
        // Nothing left to watch -- every server deleted, or every one of them
        // switched off, which reaches here as the same empty list.
        if (servers.isEmpty()) {
            reconciler.forgetAll()
            stopSelf()
        }
    }

    /**
     * Whether a job started for [before] would be started the same way for
     * [now]: the address, the credentials, and what to announce.
     *
     * Compared field by field rather than through a hash of them. This is the
     * only thing that notices an edit at all, so a collision would silently go
     * on using a password the user has already corrected -- which is the bug
     * this test exists to catch, made invisible.
     *
     * `lastGoodUrl` is deliberately not here. The client writes it back on every
     * successful failover, which re-emits the server list, which would restart
     * the job that had just succeeded -- forever.
     *
     * Neither is `enabled`, and for a different reason: the list this service
     * sees is already filtered to the switched-on ones, so a server changing
     * that arrives here as an appearance or a disappearance, never as an edit.
     */
    private fun sameWatch(before: Server?, now: Server): Boolean =
        before != null &&
            before.urls == now.urls &&
            before.username == now.username &&
            before.password == now.password &&
            before.notifyEnabled == now.notifyEnabled &&
            before.notifyNeedsYou == now.notifyNeedsYou &&
            before.notifyFinished == now.notifyFinished

    private suspend fun restartAll() = jobsLock.withLock {
        val running = jobs.keys.toList()
        running.forEach { id -> jobs.remove(id)?.cancelAndJoin() }
        running.forEach { id -> jobs[id] = watch(id) }
    }

    /**
     * One server, forever: reach it, read the bell and what is alive, then hold
     * the event stream and read again whenever the server says something moved.
     */
    private fun watch(serverId: String): Job = scope.launch {
        var backoff = FIRST_BACKOFF_MS
        while (isActive) {
            val server = graph.store.get(serverId) ?: return@launch

            val connection = graph.client.connect(server)
            if (connection !is Connection.Ready) {
                Log.i(TAG, "cannot reach " + server.label() + ": " + shortOf(connection))
                graph.watch.lost(serverId, shortOf(connection), refused = connection is Connection.Refused)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            backoff = FIRST_BACKOFF_MS
            val base = connection.baseUrl

            Log.i(TAG, "connected to " + server.label() + " on " + base)
            graph.watch.reached(serverId)
            sync(serverId, base)
            count(serverId, base)

            // The stream is the only thing that blocks. Two conflated channels
            // beside it rather than one: a burst of status flips must not
            // conflate away the bell change that landed in the middle of it,
            // and a slow live read must not hold up a notification.
            coroutineScope {
                val bell = Channel<Unit>(Channel.CONFLATED)
                val live = Channel<Unit>(Channel.CONFLATED)
                val readers = listOf(
                    launch {
                        for (unused in bell) {
                            sync(serverId, base)
                            // A stop IS a change of status, but the server only
                            // announces `live-changed` for what it can see in
                            // the pid files -- and a composer session of its own
                            // putting a permission on screen is not in there. It
                            // moves the bell and nothing else, and without this
                            // the card would go on saying "working" about a
                            // session that is waiting for you, which is the one
                            // number that must never be wrong.
                            live.trySend(Unit)
                        }
                    },
                    launch {
                        for (unused in live) {
                            // Answering a permission flips busy and waiting back
                            // and forth, and every flip is an event. Letting the
                            // burst settle first turns it into one read -- the
                            // only place this is rate-limited, so the notice and
                            // the screen both get it for free.
                            delay(LIVE_SETTLE_MS)
                            count(serverId, base)
                        }
                    },
                )
                try {
                    graph.client.streamEvents(base) { type ->
                        when (type) {
                            EVENT_NOTIFICATIONS -> bell.trySend(Unit)
                            EVENT_LIVE -> live.trySend(Unit)
                        }
                    }
                } finally {
                    // Leaving them open is not a leak, it is a hang: the readers
                    // sit waiting on the channel and this scope waits for them.
                    bell.close()
                    live.close()
                }
                readers.joinAll()
            }

            Log.i(TAG, "stream ended for " + serverId + ", reconnecting in " + backoff + " ms")
            graph.watch.lost(serverId, "Reconnecting")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /**
     * Read the bell and make the phone match it. The preferences are re-read
     * every time rather than remembered: they live on the server, and a switch
     * flipped there should reach the phone on the next thing that happens.
     */
    private suspend fun sync(serverId: String, base: String) {
        val server = graph.store.get(serverId) ?: return
        val rows = graph.client.notifications(server, base) ?: return
        val prefs = server.effectiveNotify(graph.client.serverSettings(server, base))
        Log.i(TAG, "bell for " + server.label() + ": " + rows.size + " stopped, notify=" + prefs)
        graph.watch.muted(serverId, prefs.silent())
        // The raw bell, before the preferences have had a say: what is finished
        // rather than merely idle is a fact about that server, and silencing one
        // makes it quiet, not uncounted.
        graph.watch.pending(serverId, rows.mapTo(mutableSetOf()) { it.sessionId })
        reconciler.apply(server, rows, prefs)
    }

    /**
     * Count what is alive over there.
     *
     * The log line is the instrument. `ignoreUnknownKeys` and a default on every
     * field mean a name this app spelled wrong would read as an empty status
     * forever and look exactly like a quiet machine -- so what was counted is
     * written down, and so is how many rows would not say.
     */
    private suspend fun count(serverId: String, base: String) {
        val server = graph.store.get(serverId) ?: return
        val rows = graph.client.live(server, base) ?: return
        val live = LiveSnapshot.of(rows)
        // What the state made of it once it was crossed with the bell, rather
        // than a second sum worked out beside it: the log is meant to be the
        // number that was BELIEVED.
        val counts = graph.watch.counted(serverId, live) ?: LiveCounts.of(live)
        val unsaid = live.rows - counts.total
        Log.i(
            TAG,
            "live on " + server.label() + ": " + counts.say().ifEmpty { "nothing open" } +
                if (unsaid > 0) " (" + unsaid + " not saying)" else "",
        )
    }

    private fun shortOf(connection: Connection): String = when (connection) {
        is Connection.Ready -> WatchState.CONNECTED
        is Connection.Refused -> connection.short
        is Connection.Unreachable -> "Not reachable"
    }

    /** The permanent notice, reduced to the two strings it draws as. */
    private data class Notice(val summary: String, val detail: String)

    private fun notice(servers: List<Server>, live: Map<String, ServerLive>): Notice {
        val lines = servers.map { it.label() + " — " + say(live[it.id]) }
        val total = servers.mapNotNull { live[it.id]?.counts }.fold(LiveCounts.NONE, LiveCounts::plus)
        val connected = servers.count { live[it.id]?.connected == true }
        val summary = when {
            servers.isEmpty() -> "No servers configured"
            // What somebody pulled the shade down to find out. Only while there
            // is something to find out: with nothing running anywhere, how many
            // servers are up is the more useful sentence.
            total.total > 0 -> total.say()
            servers.size == 1 -> lines.first()
            else -> "$connected of ${servers.size} servers connected"
        }
        return Notice(summary, lines.joinToString("\n"))
    }

    /** One server's line: what it is holding, or why it is holding nothing. */
    private fun say(state: ServerLive?): String {
        if (state == null) return WatchState.STARTING
        val counts = state.counts
        val body = when {
            counts == null -> state.connection
            counts.total == 0 -> "Nothing open"
            else -> counts.say()
        }
        // Which is the answer to why a server that is plainly up says nothing.
        return if (state.muted) "$body · muted" else body
    }

    private fun startForegroundNotice() {
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        val first = notice(graph.store.servers.value.watched(), graph.watch.servers.value)
        ServiceCompat.startForeground(this, Notifications.WATCHING_ID, build(first), type)
    }

    // The permission can be refused or revoked at any moment, and the runCatching
    // is the handling: a notice that cannot be redrawn is not worth a crash, and
    // the service goes on watching either way.
    @SuppressLint("MissingPermission")
    private fun show(notice: Notice) {
        runCatching { NotificationManagerCompat.from(this).notify(Notifications.WATCHING_ID, build(notice)) }
    }

    private fun build(notice: Notice): Notification {
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_WATCHING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Watching claude-history")
            .setContentText(notice.summary)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp)
        // Expanded, each server says its own numbers, which is the only place an
        // aggregate can be taken apart again -- but only when that is more than
        // the line above it. With one server and nothing running, the two are
        // the same sentence, and pulling it open would cost shade height to be
        // told again what it already said.
        if (notice.detail.isNotEmpty() && notice.detail != notice.summary) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(notice.detail))
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "claude-history"

        private const val FIRST_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L

        /** How long a burst of status flips is given to settle before counting. */
        private const val LIVE_SETTLE_MS = 400L

        private const val EVENT_NOTIFICATIONS = "notifications-changed"
        private const val EVENT_LIVE = "live-changed"

        /** Reachable from anywhere that knows there is something to watch. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
