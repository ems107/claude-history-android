package io.github.ems107.claudehistory.notify

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The service that makes this app worth installing: it holds a connection to
 * every server you asked to be told about, and mirrors their bells onto the
 * phone.
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

    private val status = mutableMapOf<String, String>()
    private val statusLock = Mutex()

    private val graph get() = application as ClaudeHistoryApp
    private val reconciler get() = graph.reconciler

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
        super.onDestroy()
    }

    private suspend fun syncJobs(servers: List<Server>) = jobsLock.withLock {
        val ids = servers.map { it.id }.toSet()
        jobs.keys.filter { it !in ids }.forEach { gone ->
            jobs.remove(gone)?.cancelAndJoin()
            reconciler.forget(gone)
            setStatus(gone, null)
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
     * One server, forever: reach it, read the bell, then hold the event stream
     * and read it again whenever the server says something changed.
     */
    private fun watch(serverId: String): Job = scope.launch {
        var backoff = FIRST_BACKOFF_MS
        while (isActive) {
            val server = graph.store.get(serverId) ?: return@launch

            val connection = graph.client.connect(server)
            if (connection !is Connection.Ready) {
                Log.i(TAG, "cannot reach " + server.label() + ": " + shortOf(connection))
                setStatus(serverId, shortOf(connection))
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            backoff = FIRST_BACKOFF_MS

            val prefs = server.effectiveNotify(graph.client.serverSettings(server, connection.baseUrl))
            if (prefs.silent()) {
                setStatus(serverId, "Notifications off")
                reconciler.forget(serverId)
                delay(SILENT_RECHECK_MS)
                continue
            }

            Log.i(TAG, "connected to " + server.label() + " on " + connection.baseUrl)
            setStatus(serverId, "Connected")
            sync(serverId, connection.baseUrl)

            // The stream is the only thing that blocks; a conflated channel
            // beside it turns a burst of events into one re-read.
            coroutineScope {
                val pokes = Channel<Unit>(Channel.CONFLATED)
                val reader = launch {
                    for (unused in pokes) sync(serverId, connection.baseUrl)
                }
                graph.client.streamEvents(connection.baseUrl) { type ->
                    if (type == "notifications-changed") pokes.trySend(Unit)
                }
                pokes.close()
                reader.join()
            }

            Log.i(TAG, "stream ended for " + serverId + ", reconnecting in " + backoff + " ms")
            setStatus(serverId, "Reconnecting")
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
        reconciler.apply(server, rows, prefs)
    }

    private fun shortOf(connection: Connection): String = when (connection) {
        is Connection.Ready -> "Connected"
        is Connection.Refused -> connection.short
        is Connection.Unreachable -> "Not reachable"
    }

    private suspend fun setStatus(serverId: String, text: String?) {
        statusLock.withLock {
            if (text == null) status.remove(serverId) else status[serverId] = text
        }
        updateNotice()
    }

    private fun startForegroundNotice() {
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, Notifications.WATCHING_ID, buildNotice(), type)
    }

    private fun updateNotice() {
        runCatching { NotificationManagerCompat.from(this).notify(Notifications.WATCHING_ID, buildNotice()) }
    }

    private fun buildNotice(): Notification {
        val lines = graph.store.servers.value.map { server ->
            "${server.label()} — ${status[server.id] ?: "Starting"}"
        }
        val connected = status.values.count { it == "Connected" }
        val summary = when {
            lines.isEmpty() -> "No servers configured"
            lines.size == 1 -> lines.first()
            else -> "$connected of ${lines.size} servers connected"
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_WATCHING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Watching claude-history")
            .setContentText(summary)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
        if (lines.size > 1) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "claude-history"

        private const val FIRST_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L

        /** A server whose notifications are off is still worth re-asking, rarely. */
        private const val SILENT_RECHECK_MS = 10 * 60_000L

        /** Reachable from anywhere that knows there is something to watch. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
