package io.github.ems107.claudehistory.notify

import io.github.ems107.claudehistory.net.LIVE_BUSY
import io.github.ems107.claudehistory.net.LIVE_STOPPED
import io.github.ems107.claudehistory.net.LIVE_WAITING
import io.github.ems107.claudehistory.net.LiveRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * How many sessions are doing each of the three things.
 *
 * A status this app has never heard of is counted in NONE of them. That is the
 * server's own rule about the same field: the CLI's four values were read out
 * of the binary rather than guessed, and a fifth one appearing later has to
 * degrade to "cannot say" instead of quietly arriving as idle.
 */
data class LiveCounts(val waiting: Int, val working: Int, val idle: Int) {

    val total: Int get() = waiting + working + idle

    /**
     * "2 waiting, 1 working" -- the zeros left out, because a `0 idle` is a
     * number you have to read before you can ignore it. Empty when all three
     * are zero, which is a sentence the caller has to write for itself: what
     * "nothing" means differs between a line in a list and a line in a notice.
     */
    fun say(): String = buildList {
        if (waiting > 0) add("$waiting waiting")
        if (working > 0) add("$working working")
        if (idle > 0) add("$idle idle")
    }.joinToString(", ")

    operator fun plus(other: LiveCounts): LiveCounts =
        LiveCounts(waiting + other.waiting, working + other.working, idle + other.idle)

    companion object {
        val NONE = LiveCounts(0, 0, 0)

        fun of(rows: List<LiveRow>): LiveCounts = LiveCounts(
            waiting = rows.count { it.status == LIVE_WAITING },
            working = rows.count { it.status == LIVE_BUSY },
            idle = rows.count { it.status in LIVE_STOPPED },
        )
    }
}

/**
 * What the watching service knows about one server at this moment.
 *
 * `counts` is null for "not said", never for "nothing" -- a server that cannot
 * be reached has not told us there is nothing open, it has told us nothing at
 * all, and drawing that as three zeros would be inventing an answer. Same rule
 * that already governs the notifications themselves: losing a server does not
 * clear what it last said.
 */
data class ServerLive(
    val connection: String,
    val connected: Boolean = false,
    val counts: LiveCounts? = null,
    /** Its notifications are off. It is still watched; it just raises nothing. */
    val muted: Boolean = false,
    /** Reached and turned away: a wrong password, remote access off. Worth red. */
    val refused: Boolean = false,
)

/**
 * The one place the service writes down what it is seeing, and the only way the
 * screen finds out.
 *
 * Before this there were two answers to "is that server up": the service's own
 * private map, which was right, and the list screen's, which was a single
 * `connect()` from whenever you last opened the screen. The notification shade
 * knew more than the app did.
 *
 * A `StateFlow` of an immutable map rather than a locked `mutableMapOf`: the
 * old one was written under a mutex and then read outside it by `buildNotice`,
 * on another thread, which is a `ConcurrentModificationException` waiting for a
 * busy afternoon.
 */
class WatchState {

    private val _servers = MutableStateFlow<Map<String, ServerLive>>(emptyMap())
    val servers: StateFlow<Map<String, ServerLive>> = _servers.asStateFlow()

    /** Signed in and about to hold the stream. */
    fun reached(serverId: String) = write(serverId) { before ->
        // The counts start again from "not said": whatever they were belongs to
        // the connection that just ended, and the fresh read is one line away.
        // Muted is not ours to guess -- `sync` reads it from the server.
        ServerLive(connection = CONNECTED, connected = true, counts = null, muted = before?.muted ?: false)
    }

    /** Unreachable, refused, or the stream ended: [why] is what the card says. */
    fun lost(serverId: String, why: String, refused: Boolean = false) = write(serverId) { before ->
        ServerLive(
            connection = why,
            connected = false,
            counts = null,
            muted = before?.muted ?: false,
            refused = refused,
        )
    }

    /**
     * The two that say ONE thing each, and know nothing about the rest.
     *
     * A row that is not there belongs to a server nobody is watching any more --
     * the job cancelled, the server deleted -- and inventing the other fields in
     * order to carry this one would put a card back on the screen claiming a
     * connection that does not exist. So they patch, or they do nothing.
     */
    fun counted(serverId: String, counts: LiveCounts) = patch(serverId) { it.copy(counts = counts) }

    fun muted(serverId: String, muted: Boolean) = patch(serverId) { it.copy(muted = muted) }

    private fun patch(serverId: String, change: (ServerLive) -> ServerLive) {
        _servers.update { all -> all[serverId]?.let { all + (serverId to change(it)) } ?: all }
    }

    fun forget(serverId: String) {
        _servers.update { it - serverId }
    }

    /**
     * The service is gone. Everything in here was an assertion about right now,
     * and there is no longer anybody making it -- leaving the last one on screen
     * would be a card saying "Connected" on behalf of nothing.
     */
    fun clear() {
        _servers.value = emptyMap()
    }

    private fun write(serverId: String, change: (ServerLive?) -> ServerLive) {
        _servers.update { it + (serverId to change(it[serverId])) }
    }

    companion object {
        const val CONNECTED = "Signed in"

        /** No word from the service yet -- it is starting, or it is not running. */
        const val STARTING = "Starting"
    }
}
