package io.github.ems107.claudehistory.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One claude-history server, as the app holds it in memory: password in the
 * clear, because everything that uses it needs it that way. What reaches the
 * disk is [StoredServer], where it is ciphertext.
 *
 * `urls` is ordered and is the whole failover story: the LAN address and the
 * tunnel address are the same server, and which one answers depends on where the
 * phone is. `lastGoodUrl` is tried first so the common case costs one request --
 * it is a memory of what worked, never a preference the user set.
 */
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val alias: String = "",
    val urls: List<String> = emptyList(),
    val username: String = "",
    val password: String = "",
    val lastGoodUrl: String? = null,
) {
    /** The order to try: what worked last time, then everything else as written. */
    fun candidates(): List<String> {
        val good = lastGoodUrl?.takeIf { it in urls }
        return if (good == null) urls else listOf(good) + urls.filter { it != good }
    }

    fun label(): String = alias.ifBlank { urls.firstOrNull() ?: "(no address)" }
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
)

fun Server.toStored(): StoredServer =
    StoredServer(id, alias, urls, username, Secrets.encrypt(password), lastGoodUrl)

fun StoredServer.toServer(): Server =
    Server(id, alias, urls, username, Secrets.decrypt(password), lastGoodUrl)
