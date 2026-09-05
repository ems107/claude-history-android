package io.github.ems107.claudehistory.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.ems107.claudehistory.MainActivity
import io.github.ems107.claudehistory.R
import io.github.ems107.claudehistory.data.EffectiveNotify
import io.github.ems107.claudehistory.data.Server
import io.github.ems107.claudehistory.net.KIND_FINISHED
import io.github.ems107.claudehistory.net.KIND_NEEDS_YOU
import io.github.ems107.claudehistory.net.StoppedRow
import java.util.concurrent.ConcurrentHashMap

object Notifications {
    /** A session that is waiting for a person: the whole point of the app. */
    const val CHANNEL_NEEDS_YOU = "needs-you"

    /** A session that finished answering. Quieter, and often switched off. */
    const val CHANNEL_FINISHED = "finished"

    /** The permanent one Android requires while the app is watching. */
    const val CHANNEL_WATCHING = "watching"

    const val WATCHING_ID = 1

    const val EXTRA_SERVER_ID = "serverId"
    const val EXTRA_SESSION_ID = "sessionId"
    const val EXTRA_KEY = "key"
    const val EXTRA_AT = "at"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEEDS_YOU,
                "Waiting for you",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "A session has stopped and needs an answer." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FINISHED,
                "Finished",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "A session finished answering." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WATCHING,
                "Watching",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description =
                    "The permanent notice Android requires while the app holds its connection to your servers."
                setShowBadge(false)
            },
        )
    }

    fun keyOf(serverId: String, sessionId: String): String = "$serverId|$sessionId"

    /** Stable per session and server, so the same stop always lands on the same row. */
    fun idOf(key: String): Int = key.hashCode() and 0x7FFFFFFF
}

/**
 * The phone shows exactly what the bell shows.
 *
 * That is the rule this class exists to keep, and it decides every branch in it:
 * a row that appears is raised, a row that goes is withdrawn -- whoever attended
 * it, wherever they were -- and a row that merely persists is left alone. There
 * is no history here and nothing survives a restart, because the bell itself is
 * a memory of transitions that the server keeps in RAM and loses on its own
 * restart ([docs/AI_SERVER_CONTRACT.md]).
 *
 * The one thing that is ours rather than the server's is what you have already
 * seen: swiping a notification away, or opening it, acknowledges that stop. It
 * comes back only if the session stops AGAIN, which is a different `at`.
 */
class Reconciler(private val context: Context) {

    private val shown = ConcurrentHashMap<String, Long>()
    private val acknowledged = ConcurrentHashMap<String, Long>()

    private val manager get() = NotificationManagerCompat.from(context)

    fun apply(server: Server, rows: List<StoppedRow>, prefs: EffectiveNotify) {
        val wanted = if (!prefs.enabled) emptyList() else rows.filter {
            when (it.kind) {
                KIND_NEEDS_YOU -> prefs.needsYou
                KIND_FINISHED -> prefs.finished
                else -> false
            }
        }
        val wantedByKey = wanted.associateBy { Notifications.keyOf(server.id, it.sessionId) }

        // Gone from the server is gone from the phone. This is the half that
        // makes attending a session at the desk clear the notification here.
        val prefix = server.id + "|"
        shown.keys.filter { it.startsWith(prefix) && it !in wantedByKey }.forEach { key ->
            Log.i("claude-history", "withdrew " + key)
            manager.cancel(Notifications.idOf(key))
            shown.remove(key)
            acknowledged.remove(key)
        }

        wantedByKey.forEach { (key, row) ->
            if (acknowledged[key] == row.at) return@forEach
            if (shown[key] == row.at) return@forEach
            if (!post(server, key, row)) return@forEach
            Log.i("claude-history", "raised " + row.kind + " for " + (row.title ?: row.sessionId))
            shown[key] = row.at
            acknowledged.remove(key)
        }
    }

    /** Swiped away or opened: seen, and not to be raised again for the same stop. */
    fun acknowledge(key: String, at: Long) {
        acknowledged[key] = at
        shown.remove(key)
        manager.cancel(Notifications.idOf(key))
    }

    /** A server that was deleted, or whose notifications were switched off. */
    fun forget(serverId: String) {
        val prefix = serverId + "|"
        shown.keys.filter { it.startsWith(prefix) }.forEach { key ->
            manager.cancel(Notifications.idOf(key))
            shown.remove(key)
            acknowledged.remove(key)
        }
    }

    fun forgetAll() {
        shown.keys.toList().forEach { manager.cancel(Notifications.idOf(it)) }
        shown.clear()
        acknowledged.clear()
    }

    private fun post(server: Server, key: String, row: StoppedRow): Boolean {
        val id = Notifications.idOf(key)
        val needsYou = row.kind == KIND_NEEDS_YOU

        val open = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(Notifications.EXTRA_SERVER_ID, server.id)
            putExtra(Notifications.EXTRA_SESSION_ID, row.sessionId)
            putExtra(Notifications.EXTRA_KEY, key)
            putExtra(Notifications.EXTRA_AT, row.at)
            // Two notifications from different sessions must not be treated as
            // the same intent, which is what a bare extras difference would be.
            data = android.net.Uri.parse("claude-history://session/${server.id}/${row.sessionId}")
        }
        val dismiss = Intent(context, DismissReceiver::class.java).apply {
            putExtra(Notifications.EXTRA_KEY, key)
            putExtra(Notifications.EXTRA_AT, row.at)
            data = android.net.Uri.parse("claude-history://dismiss/$key")
        }

        val notification = NotificationCompat.Builder(
            context,
            if (needsYou) Notifications.CHANNEL_NEEDS_YOU else Notifications.CHANNEL_FINISHED,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(row.title?.takeIf { it.isNotBlank() } ?: "Untitled session")
            .setContentText(describe(row))
            .setSubText(subText(server, row))
            .setStyle(NotificationCompat.BigTextStyle().bigText(describe(row)))
            .setWhen(row.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(if (needsYou) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (needsYou) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    id,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context,
                    id,
                    dismiss,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

        return try {
            manager.notify(id, notification)
            true
        } catch (_: SecurityException) {
            // The notification permission was refused or revoked. Nothing to do
            // about it here, and nothing worth crashing for.
            false
        }
    }

    private fun describe(row: StoppedRow): String = when (row.kind) {
        KIND_NEEDS_YOU -> row.waitingFor?.takeIf { it.isNotBlank() }
            ?.let { "Waiting for you — $it" }
            ?: "Waiting for you"

        else -> "Finished"
    }

    private fun subText(server: Server, row: StoppedRow): String {
        val project = row.projectName?.takeIf { it.isNotBlank() }
        return if (project == null) server.label() else "${server.label()} · $project"
    }
}
