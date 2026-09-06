package io.github.ems107.claudehistory.notify

import io.github.ems107.claudehistory.net.LIVE_BUSY
import io.github.ems107.claudehistory.net.LIVE_STOPPED
import io.github.ems107.claudehistory.net.LIVE_WAITING
import io.github.ems107.claudehistory.net.LiveRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The four things a session can be doing, in the order they are ever said in. */
enum class LiveKind { WAITING, WORKING, FINISHED, IDLE }

/**
 * What `/api/live` said, kept in the only shape the counts depend on.
 *
 * The rows themselves are not kept. Two reasons, and the second is the one that
 * matters: a wire DTO parked in the state a screen reads mixes the two layers,
 * and it carries fields that move without moving any number -- `waitingFor`
 * changes as a dialog changes -- which would have the list emitting for nothing.
 *
 * The stopped ones are held BY NAME rather than counted, because which of them
 * is finished is not known until they are crossed with the bell. [rows] is the
 * raw total, so the `(N not saying)` residue still adds up.
 */
data class LiveSnapshot(
    val waiting: Int,
    val working: Int,
    val stopped: Set<String>,
    val rows: Int,
) {
    companion object {
        fun of(rows: List<LiveRow>): LiveSnapshot = LiveSnapshot(
            waiting = rows.count { it.status == LIVE_WAITING },
            working = rows.count { it.status == LIVE_BUSY },
            stopped = rows.filter { it.status in LIVE_STOPPED }.mapTo(mutableSetOf()) { it.sessionId },
            rows = rows.size,
        )
    }
}

/**
 * How many sessions are doing each of the four things.
 *
 * A status this app has never heard of is counted in NONE of them. That is the
 * server's own rule about the same field: the CLI's four values were read out
 * of the binary rather than guessed, and a fifth one appearing later has to
 * degrade to "cannot say" instead of quietly arriving as idle.
 *
 * **`finished` is the one the server does not have.** The CLI reports a session
 * that has just ended its turn and a terminal somebody left open on Tuesday as
 * the same `idle`, and only one of the two is asking for anything. The bell is
 * the difference: a stopped session it is still holding has not been looked at
 * yet. So `finished` is carved OUT of idle rather than added beside it -- the
 * four still sum to the same total -- and the moment anybody reads that row,
 * anywhere, it goes back to being idle on its own.
 */
data class LiveCounts(val waiting: Int, val working: Int, val finished: Int, val idle: Int) {

    val total: Int get() = waiting + working + finished + idle

    /**
     * The fragments, in their fixed order and without the zeros -- a `0 idle` is
     * a number you have to read before you can ignore it.
     *
     * One home for them, because there are two renderers: the notice joins them
     * with commas and the card draws each in its own colour. Written out twice,
     * a fourth number is half of a fifth one written wrong.
     */
    fun parts(): List<Pair<LiveKind, String>> = buildList {
        if (waiting > 0) add(LiveKind.WAITING to "$waiting waiting")
        if (working > 0) add(LiveKind.WORKING to "$working working")
        if (finished > 0) add(LiveKind.FINISHED to "$finished finished")
        if (idle > 0) add(LiveKind.IDLE to "$idle idle")
    }

    /**
     * "2 waiting, 1 finished" -- empty when all four are zero, which is a
     * sentence the caller has to write for itself: what "nothing" means differs
     * between a line in a list and a line in a notice.
     */
    fun say(): String = parts().joinToString(", ") { it.second }

    operator fun plus(other: LiveCounts): LiveCounts = LiveCounts(
        waiting + other.waiting,
        working + other.working,
        finished + other.finished,
        idle + other.idle,
    )

    companion object {
        val NONE = LiveCounts(0, 0, 0, 0)

        fun of(live: LiveSnapshot, pending: Set<String> = emptySet()): LiveCounts {
            val finished = live.stopped.count { it in pending }
            return LiveCounts(
                waiting = live.waiting,
                working = live.working,
                finished = finished,
                idle = live.stopped.size - finished,
            )
        }
    }
}

/**
 * What the watching service knows about one server at this moment.
 *
 * `counts` is null for "not said", never for "nothing" -- a server that cannot
 * be reached has not told us there is nothing open, it has told us nothing at
 * all, and drawing that as four zeros would be inventing an answer. Same rule
 * that already governs the notifications themselves: losing a server does not
 * clear what it last said.
 *
 * The two halves are stored and the counts are DERIVED, rather than the other
 * way round. They arrive on separate coroutines -- the bell reader, and the live
 * reader 400 ms behind it on purpose -- so crossing them at the moment of
 * writing would mean one half waiting for the other. Crossing them when they are
 * READ means each writes as it arrives, in any order, and whoever looks always
 * sees the cross of the two latest.
 */
data class ServerLive(
    val connection: String,
    val connected: Boolean = false,
    /** Its notifications are off. It is still watched; it just raises nothing. */
    val muted: Boolean = false,
    /** Reached and turned away: a wrong password, remote access off. Worth red. */
    val refused: Boolean = false,
    /** What `/api/live` last said, or null for "not said" -- never "nothing". */
    val live: LiveSnapshot? = null,
    /** The sessions the bell is holding, which is what tells an idle one from a finished one. */
    val pending: Set<String> = emptySet(),
) {
    val counts: LiveCounts? get() = live?.let { LiveCounts.of(it, pending) }
}

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
        // Both halves start again from "not said": whatever they were belongs to
        // the connection that just ended, and the fresh reads are one line away.
        // Muted is not ours to guess -- `sync` reads it from the server.
        ServerLive(connection = CONNECTED, connected = true, muted = before?.muted ?: false)
    }

    /** Unreachable, refused, or the stream ended: [why] is what the card says. */
    fun lost(serverId: String, why: String, refused: Boolean = false) = write(serverId) { before ->
        ServerLive(
            connection = why,
            connected = false,
            muted = before?.muted ?: false,
            refused = refused,
        )
    }

    /**
     * The three that say ONE thing each, and know nothing about the rest.
     *
     * A row that is not there belongs to a server nobody is watching any more --
     * the job cancelled, the server deleted -- and inventing the other fields in
     * order to carry this one would put a card back on the screen claiming a
     * connection that does not exist. So they patch, or they do nothing.
     *
     * [counted] hands back what the cross came to, so the log line can say the
     * number that was actually believed rather than one worked out beside it.
     */
    fun counted(serverId: String, live: LiveSnapshot): LiveCounts? =
        patch(serverId) { it.copy(live = live) }?.counts

    /** What the bell is holding. Raw: muting a server makes it quiet, not uncounted. */
    fun pending(serverId: String, sessionIds: Set<String>) {
        patch(serverId) { it.copy(pending = sessionIds) }
    }

    fun muted(serverId: String, muted: Boolean) {
        patch(serverId) { it.copy(muted = muted) }
    }

    private fun patch(serverId: String, change: (ServerLive) -> ServerLive): ServerLive? {
        var after: ServerLive? = null
        _servers.update { all ->
            val before = all[serverId]
            if (before == null) {
                after = null
                all
            } else {
                val next = change(before)
                after = next
                all + (serverId to next)
            }
        }
        return after
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
