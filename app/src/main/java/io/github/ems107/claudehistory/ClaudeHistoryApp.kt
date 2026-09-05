package io.github.ems107.claudehistory

import android.app.Application
import io.github.ems107.claudehistory.data.ServerStore
import io.github.ems107.claudehistory.net.ServerClient

/**
 * The whole object graph, and it is two objects.
 *
 * They live on the Application rather than in a ViewModel because the watching
 * service will need exactly the same two, and a second copy of either would mean
 * a second server list and a second set of cookies.
 */
class ClaudeHistoryApp : Application() {
    val store: ServerStore by lazy { ServerStore(this) }
    val client: ServerClient by lazy { ServerClient(store) }
}
