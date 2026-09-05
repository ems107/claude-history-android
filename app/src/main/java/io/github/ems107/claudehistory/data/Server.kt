package io.github.ems107.claudehistory.data

import io.github.ems107.claudehistory.net.ServerSettings
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * What this app has been told about one kind of notification.
 *
 * [INHERIT] is the default and means "whatever that server's own settings say"
 * -- claude-history already knows what its owner wants to be told about, and
 * disagreeing with it by default would be the app deciding it knows better. The
 * other two are for the case the phone really is different from the desk.
 */
enum class Toggle { INHERIT, ON, OFF }

/**
 * One claude-history server, as the app holds it in memory: password in the
 * clear, because everything that uses it needs it that way. What reaches the
 * disk is [StoredServer], where it is ciphertext.
 *
 * `urls` is ordered and is the whole failover story: the LAN address and the
 * tunnel address are the same server, and which one answers depends on where the
 * phone is. `lastGoodUrl` is tried first so the common case costs one request --
 * it is a memory of what worked, never a preference anyone set.
 */
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val alias: String = "",
    val urls: List<String> = emptyList(),
    val username: String = "",
    val password: String = "",
    val lastGoodUrl: String? = null,
    val notifyEnabled: Toggle = Toggle.INHERIT,
    val notifyNeedsYou: Toggle = Toggle.INHERIT,
    val notifyFinished: Toggle = Toggle.INHERIT,
) {
    /** The order to try: what worked last time, then everything else as written. */
    fun candidates(): List<String> {
        val good = lastGoodUrl?.takeIf { it in urls }
        return if (good == null) urls else listOf(good) + urls.filter { it != good }
    }

    fun label(): String = alias.ifBlank { urls.firstOrNull() ?: "(no address)" }

    /**
     * What to actually notify about, once the server has been asked what it
     * thinks. A server that could not be asked falls back to its defaults, which
     * are the same as claude-history's own: everything on.
     */
    fun effectiveNotify(settings: ServerSettings?): EffectiveNotify {
        val theirs = settings ?: ServerSettings()
        fun resolve(mine: Toggle, theirsValue: Boolean) = when (mine) {
            Toggle.INHERIT -> theirsValue
            Toggle.ON -> true
            Toggle.OFF -> false
        }
        return EffectiveNotify(
            enabled = resolve(notifyEnabled, theirs.notifyEnabled),
            needsYou = resolve(notifyNeedsYou, theirs.notifyOnNeedsYou),
            finished = resolve(notifyFinished, theirs.notifyOnFinished),
        )
    }
}

data class EffectiveNotify(
    val enabled: Boolean,
    val needsYou: Boolean,
    val finished: Boolean,
) {
    /** Nothing to watch for at all: the connection need not even be opened. */
    fun silent(): Boolean = !enabled || (!needsYou && !finished)
}

@Serializable
data class StoredServer(
    val id: String,
    val alias: String,
    val urls: List<String>,
    val username: String,
    /** Ciphertext from [Secrets]. Never the password itself. */
    val password: String,
    val lastGoodUrl: String? = null,
    val notifyEnabled: Toggle = Toggle.INHERIT,
    val notifyNeedsYou: Toggle = Toggle.INHERIT,
    val notifyFinished: Toggle = Toggle.INHERIT,
)

fun Server.toStored(): StoredServer = StoredServer(
    id = id,
    alias = alias,
    urls = urls,
    username = username,
    password = Secrets.encrypt(password),
    lastGoodUrl = lastGoodUrl,
    notifyEnabled = notifyEnabled,
    notifyNeedsYou = notifyNeedsYou,
    notifyFinished = notifyFinished,
)

fun StoredServer.toServer(): Server = Server(
    id = id,
    alias = alias,
    urls = urls,
    username = username,
    password = Secrets.decrypt(password),
    lastGoodUrl = lastGoodUrl,
    notifyEnabled = notifyEnabled,
    notifyNeedsYou = notifyNeedsYou,
    notifyFinished = notifyFinished,
)
