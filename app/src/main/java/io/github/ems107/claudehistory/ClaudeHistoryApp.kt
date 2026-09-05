package io.github.ems107.claudehistory

import android.app.Application
import io.github.ems107.claudehistory.data.ServerStore
import io.github.ems107.claudehistory.net.ServerClient
import io.github.ems107.claudehistory.notify.Reconciler
import io.github.ems107.claudehistory.notify.WatchState

/**
 * The whole object graph, and it is four objects.
 *
 * They live on the Application rather than in a ViewModel because the watching
 * service needs the same four, and a second copy of any of them would mean a
 * second server list, a second set of cookies, a second opinion about which
 * notifications are on screen, or a screen disagreeing with the service about
 * whether a server is up.
 */
class ClaudeHistoryApp : Application() {
    val store: ServerStore by lazy { ServerStore(this) }
    val client: ServerClient by lazy { ServerClient(store) }
    val reconciler: Reconciler by lazy { Reconciler(this) }

    /**
     * What the service is seeing right now, for whoever is drawing it. Written
     * by the service, read by the screen -- and empty when the service is not
     * running, which is the whole of "is it running" that anybody needs to ask.
     */
    val watch: WatchState by lazy { WatchState() }

    /**
     * Seen: swiped away, or opened. Reachable from here because the dismissal
     * arrives as a broadcast, which can perfectly well outlive the service that
     * raised the notification.
     */
    fun acknowledge(key: String, at: Long) = reconciler.acknowledge(key, at)
}
