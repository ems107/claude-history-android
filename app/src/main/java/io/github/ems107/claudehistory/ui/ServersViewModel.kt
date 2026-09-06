package io.github.ems107.claudehistory.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ems107.claudehistory.ClaudeHistoryApp
import io.github.ems107.claudehistory.data.Server
import io.github.ems107.claudehistory.data.watched
import io.github.ems107.claudehistory.net.Connection
import io.github.ems107.claudehistory.notify.ServerLive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The server list and what each server currently answers.
 *
 * The two are deliberately separate: the list is durable and lives on disk, and
 * the state is a fact about right now that nothing should remember -- a server
 * that was unreachable in the kitchen is not unreachable, it was out of range.
 */
class ServersViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as ClaudeHistoryApp

    val servers: StateFlow<List<Server>> = graph.store.servers

    /**
     * What the watching service is seeing, which is the fresher answer whenever
     * there is one: it holds the connection continuously, while [states] is a
     * single check from whenever somebody last opened the screen.
     *
     * Empty while the service is not running -- which is the whole of "is it
     * running" that anything here needs to ask.
     */
    val live: StateFlow<Map<String, ServerLive>> = graph.watch.servers

    private val _states = MutableStateFlow<Map<String, Connection>>(emptyMap())
    val states: StateFlow<Map<String, Connection>> = _states.asStateFlow()

    private val _connecting = MutableStateFlow<Set<String>>(emptySet())
    val connecting: StateFlow<Set<String>> = _connecting.asStateFlow()

    fun serverOf(id: String): Server? = graph.store.get(id)

    fun refreshAll() {
        servers.value.watched().forEach { refresh(it.id) }
    }

    fun refresh(id: String) {
        if (id in _connecting.value) return
        val server = graph.store.get(id) ?: return
        // A server switched off is not asked anything, so nothing can answer for
        // it and no state of its own can be drawn on its card.
        if (!server.enabled) return
        _connecting.value = _connecting.value + id
        viewModelScope.launch {
            val result = graph.client.connect(server)
            _states.value = _states.value + (id to result)
            _connecting.value = _connecting.value - id
        }
    }

    fun save(server: Server) {
        graph.store.upsert(server)
        // Switching one off drops what it last answered, rather than leaving the
        // card quoting a "Signed in" from before it was parked.
        if (server.enabled) refresh(server.id) else _states.value = _states.value - server.id
    }

    /** The list in the order given. Written once, when a drag is let go of. */
    fun reorder(ids: List<String>) = graph.store.reorder(ids)

    fun delete(id: String) {
        graph.store.delete(id)
        _states.value = _states.value - id
    }

    /** The cookie the WebView needs, once [refresh] has reached [Connection.Ready]. */
    fun sessionCookie(baseUrl: String): String? = graph.client.sessionCookie(baseUrl)
}
