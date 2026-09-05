package io.github.ems107.claudehistory.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.ems107.claudehistory.ClaudeHistoryApp

/**
 * A phone that restarts in the night must not go quiet until somebody opens the
 * app in the morning. If there is anything to watch, the service starts itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? ClaudeHistoryApp ?: return
        if (app.store.servers.value.isEmpty()) return
        WatchService.start(context)
    }
}
