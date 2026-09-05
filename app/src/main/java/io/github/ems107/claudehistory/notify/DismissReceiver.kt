package io.github.ems107.claudehistory.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.ems107.claudehistory.ClaudeHistoryApp

/**
 * Swiping a notification away is the one thing about it that is ours and not the
 * server's: the row stays on the bell -- the session really is still waiting --
 * but this phone has been told, and telling it again would be nagging.
 *
 * It comes back only when the session stops AGAIN, which is a different `at`. In
 * particular the quote arriving late does not bring it back: that is the same
 * stop, saying more about itself.
 */
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(Notifications.EXTRA_KEY) ?: return
        val at = intent.getLongExtra(Notifications.EXTRA_AT, 0L)
        (context.applicationContext as? ClaudeHistoryApp)?.acknowledge(key, at)
    }
}
