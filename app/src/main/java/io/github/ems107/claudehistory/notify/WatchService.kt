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
 * **Every configured server is watched, a muted one included.** Notification
 * preferences decide whether a stop is ANNOUNCED, not whether the server is
 * looked at: its counts are worth the same either way, and a preference changed
 * at the desk now arrives on the next event instead of within ten minutes.
 */
class WatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private val jobs = mutableMapOf<String, Job>()

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

        scope.launch {
            graph.store.servers.collect { servers -> syncJobs(servers) }
        }

        // The notice follows what the two of them SAY, not every time they
        // change: `live-changed` fires on fields that move without moving a
        // count -- a turn's clock, a session's name -- and each redraw is a
        // binder round-trip and a re-inflation in the shade.
        scope.launch {
            combine(graph.store.servers, graph.watch.servers) { servers, live -> notice(servers, live) }
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
        super.onDestroy()
    }

    private suspend fun syncJobs(servers: List<Server>) = jobsLock.withLock {
        val ids = servers.map { it.id }.toSet()
        jobs.keys.filter { it !in ids }.forEach { gone ->
            jobs.remove(gone)?.cancelAndJoin()
            reconciler.forget(gone)
            graph.watch.forget(gone)
        }
        servers.forEach { server ->
            if (jobs[server.id] == null) jobs[server.id] = watch(server.id)
        }
        if (servers.isEmpty()) {
            reconciler.forgetAll()
            stopSelf()
        }
    }

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
                graph.watch.lost(serverId, shortOf(connection))
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
                    launch { for (unused in bell) sync(serverId, base) },
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
        val counts = LiveCounts.of(rows)
        val mute = rows.size - counts.total
        Log.i(
            TAG,
            "live on " + server.label() + ": " + counts.say().ifEmpty { "nothing open" } +
                if (mute > 0) " (" + mute + " not saying)" else "",
        )
        graph.watch.counted(serverId, counts)
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
        val first = notice(graph.store.servers.value, graph.watch.servers.value)
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
        // aggregate can be taken apart again.
        if (notice.detail.isNotEmpty()) {
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
