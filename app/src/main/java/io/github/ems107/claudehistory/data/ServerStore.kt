package io.github.ems107.claudehistory.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The server list on disk: one small JSON file, written whole on every change.
 *
 * A file rather than a database because the whole thing is a handful of records
 * that are read at startup and rewritten when you edit one. Reads and writes
 * come from the UI and, later, from the watching service, so every mutation goes
 * through one synchronized method.
 */
class ServerStore(context: Context) {
    private val file = File(context.filesDir, "servers.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _servers = MutableStateFlow(read())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    fun get(id: String): Server? = _servers.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(server: Server) {
        val current = _servers.value
        val index = current.indexOfFirst { it.id == server.id }
        _servers.value = if (index >= 0) {
            current.toMutableList().also { it[index] = server }
        } else {
            current + server
        }
        write()
    }

    @Synchronized
    fun delete(id: String) {
        _servers.value = _servers.value.filterNot { it.id == id }
        write()
    }

    /**
     * Remember which address answered. Written only when it actually changed, so
     * the common case -- the same network every day -- touches no disk at all.
     */
    @Synchronized
    fun rememberGoodUrl(id: String, url: String) {
        val server = get(id) ?: return
        if (server.lastGoodUrl == url) return
        upsert(server.copy(lastGoodUrl = url))
    }

    private fun read(): List<Server> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            json.decodeFromString<List<StoredServer>>(file.readText()).map { it.toServer() }
        }
    } catch (_: Exception) {
        // A half-written or hand-edited file must not brick the app. Losing the
        // list is recoverable in a minute; not starting is not.
        emptyList()
    }

    private fun write() {
        try {
            val text = json.encodeToString(_servers.value.map { it.toStored() })
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        } catch (_: Exception) {
            // Nothing sensible to do here, and nothing worth crashing for.
        }
    }
}
