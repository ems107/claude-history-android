package io.github.ems107.claudehistory

import android.app.Application
import io.github.ems107.claudehistory.data.ServerStore
import io.github.ems107.claudehistory.net.ServerClient
import io.github.ems107.claudehistory.notify.Reconciler

/**
 * The whole object graph, and it is three objects.
 *
 * They live on the Application rather than in a ViewModel because the watching
 * service needs the same three, and a second copy of any of them would mean a
 * second server list, a second set of cookies, or a second opinion about which
 * notifications are on screen.
 */
class ClaudeHistoryApp : Application() {
    val store: ServerStore by lazy { ServerStore(this) }
    val client: ServerClient by lazy { ServerClient(store) }
    val reconciler: Reconciler by lazy { Reconciler(this) }

    /**
     * Seen: swiped away, or opened. Reachable from here because the dismissal
     * arrives as a broadcast, which can perfectly well outlive the service that
     * raised the notification.
     */
    fun acknowledge(key: String, at: Long) = reconciler.acknowledge(key, at)
}
