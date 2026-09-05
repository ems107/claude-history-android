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
import io.github.ems107.claudehistory.net.StopPreview
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
 * **"Persists" is about the stop, not about the words.** A listed row is re-read
 * rather than taken as unchanged, because the server fills in what the session
 * said as it stopped a beat AFTER raising the row: same `at`, new quote.
 * Treating that second answer as a repeat of the first is how the web spent a
 * while drawing every card without its quote. So a row whose drawn text changed
 * is redrawn, and redrawn SILENTLY -- the stop announced itself already, and
 * announcing it again because a sentence arrived late would be the app making
 * noise about its own plumbing.
 *
 * The one thing that is ours rather than the server's is what you have already
 * seen: swiping a notification away, or opening it, acknowledges that stop. It
 * comes back only if the session stops AGAIN, which is a different `at` -- and
 * a quote landing after you swiped is not a reason to bring it back either.
 * That takes two guards rather than one: the acknowledgement arrives as a
 * broadcast and a broadcast takes its time, so the second guard is the shade
 * itself, and nothing is patched onto a notification that is no longer on it.
 */
class Reconciler(private val context: Context) {

    /** What was drawn for a stop: which stop it was, and what it said. */
    private data class Shown(val at: Long, val print: Int)

    private val shown = ConcurrentHashMap<String, Shown>()
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
            Log.i(TAG, "withdrew " + key)
            manager.cancel(Notifications.idOf(key))
            shown.remove(key)
            acknowledged.remove(key)
        }

        val onScreen = onScreen()
        wantedByKey.forEach { (key, row) ->
            if (acknowledged[key] == row.at) return@forEach
            val before = shown[key]
            val print = fingerprint(server, row)
            if (before?.at == row.at && before.print == print) return@forEach

            val id = Notifications.idOf(key)
            val patch = before != null && before.at == row.at
            // Off the shade already: it was attended to, and the acknowledgement
            // is merely still in flight. Remember the words so this is not tried
            // again on the next event, and raise nothing -- posting to an id the
            // system no longer holds arrives as a NEW notification, with all the
            // noise of one.
            if (patch && onScreen != null && id !in onScreen) {
                shown[key] = Shown(row.at, print)
                return@forEach
            }
            // A stop nothing here remembers whose row is nevertheless on the
            // shade is this process having been restarted underneath it. Update
            // it; do not announce a stop the phone announced before it died.
            val quietly = patch || (before == null && onScreen != null && id in onScreen)
            if (!post(server, key, row, quietly)) return@forEach
            Log.i(TAG, (if (quietly) "redrew " else "raised ") + row.kind + " for " + (row.title ?: row.sessionId))
            shown[key] = Shown(row.at, print)
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

    /**
     * Which of our notifications the shade is holding, or null for "could not
     * ask" -- which is not the same as "none", and the difference decides
     * whether a late quote is drawn or dropped.
     */
    private fun onScreen(): Set<Int>? = runCatching {
        context.getSystemService(NotificationManager::class.java)?.activeNotifications?.map { it.id }?.toSet()
    }.getOrNull()

    /**
     * The drawn text, as one number.
     *
     * Over what is DRAWN rather than over the row, on purpose: a row carries
     * fields that move on their own -- `stillOpen` is recomputed on every list,
     * a title improves as the session gets indexed -- and a notification is
     * worth redrawing when it would LOOK different, not when the JSON differs.
     */
    private fun fingerprint(server: Server, row: StoppedRow): Int =
        listOf(title(row), describe(row), subText(server, row), bigText(row)).joinToString(" ").hashCode()

    private fun post(server: Server, key: String, row: StoppedRow, quietly: Boolean): Boolean {
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
            .setContentTitle(title(row))
            .setContentText(describe(row))
            .setSubText(subText(server, row))
            // Collapsed, the state is the whole of it: it is what decides
            // whether this is worth walking to a desk for. The quote is what
            // the extra height is for, so it lives only in here -- and the
            // state is repeated, because an expanded style REPLACES the line
            // above rather than adding to it.
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText(row)))
            .setWhen(row.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(if (needsYou) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (needsYou) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            // Only ever set on a redraw. The flag suppresses sound, vibration
            // and the heads-up for an UPDATE to a notification the shade still
            // holds -- which is exactly a late quote, and never a new stop.
            .setOnlyAlertOnce(quietly)
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

    private fun title(row: StoppedRow): String =
        row.title?.takeIf { it.isNotBlank() } ?: "Untitled session"

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

    /**
     * What it says pulled open: the state, and then what the session actually
     * said as it stopped.
     *
     * Falls back to the state alone, which is what every notification used to
     * be: a server older than the quote sends none, and a turn that ended
     * holding nothing quotable has none to send.
     */
    private fun bigText(row: StoppedRow): String {
        val quote = row.preview?.let { quote(it) }.orEmpty()
        return if (quote.isEmpty()) describe(row) else describe(row) + "\n\n" + quote
    }

    /**
     * The quote, cut to what a shade will actually draw.
     *
     * Cut again here, on top of the server's 600: what runs past the bottom of
     * an expanded notification is not merely wasted, it takes the "cut at" line
     * with it -- and that line is the whole reason a quote stopping mid-word
     * reads as a long answer rather than as a bug in the app. The wording is
     * the web's, because the two halves are describing the same cut and the
     * first time one of them was reworded they would disagree.
     */
    private fun quote(preview: StopPreview): String {
        val headline = preview.label?.takeIf { it.isNotBlank() }
            ?: "Error".takeIf { preview.kind == PREVIEW_ERROR }
        val full = tidy(preview.text)
        val body = full.take(QUOTE_MAX).lines().take(QUOTE_LINES).joinToString("\n").trimEnd()
        if (headline == null && body.isEmpty()) return ""
        val note = if (preview.truncated || body.length < full.length) {
            // `chars` has a default like every field here, so a server that
            // stopped sending it would otherwise say "of 0 characters".
            "— cut at " + body.length + " of " + maxOf(preview.chars, full.length) + " characters"
        } else {
            null
        }
        return listOfNotNull(headline, body.ifEmpty { null }, note).joinToString("\n")
    }

    /**
     * Blank lines cost height a notification does not have, and a control
     * character draws as a box. A command keeps its own line breaks: the call
     * on one line and what the model said it was doing on the next is the shape
     * of the thing rather than spacing.
     */
    private fun tidy(text: String): String = text
        .map { if (it == '\n' || !it.isISOControl()) it else ' ' }
        .joinToString("")
        .lines()
        .map { it.trimEnd() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    private companion object {
        const val TAG = "claude-history"

        /** The one preview kind whose text arrives with no headline of its own. */
        const val PREVIEW_ERROR = "error"

        /** Roughly what an expanded notification draws before it runs out of shade. */
        const val QUOTE_MAX = 300
        const val QUOTE_LINES = 8
    }
}
