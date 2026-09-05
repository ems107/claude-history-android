package io.github.ems107.claudehistory.net

import io.github.ems107.claudehistory.data.Server
import io.github.ems107.claudehistory.data.ServerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * How this app talks to a claude-history server. The whole contract it depends
 * on is in docs/AI_SERVER_CONTRACT.md; three things about it live here.
 *
 * **Every POST carries `Origin`.** The server refuses a state-changing request
 * that arrives from another machine without one -- a caller with a cookie and no
 * browser is not a shape its own pages ever take -- so a login with no `Origin`
 * comes back 403, which looks exactly like a wrong password until you read the
 * body.
 *
 * **Cookies are kept in memory only.** They last 30 days on the server, but we
 * hold the credentials anyway, so signing in again after a restart costs one
 * request and saves persisting a second secret. A 401 mid-session means the same
 * thing and takes the same path.
 *
 * **Which address answers is a fact about the network, not about the server**,
 * so it is discovered rather than configured: the one that worked last time,
 * then the rest in the order they were written.
 */
class ServerClient(private val store: ServerStore) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * One jar for every server, keyed by host and port. Shared because the
     * client is: a jar per server would mean a connection pool per server for no
     * reason at all.
     */
    private class HostCookieJar : CookieJar {
        private val byHost = ConcurrentHashMap<String, List<Cookie>>()

        private fun key(url: HttpUrl) = url.host + ":" + url.port

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isNotEmpty()) byHost[key(url)] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = byHost[key(url)] ?: emptyList()

        fun sessionCookie(baseUrl: String): String? {
            val url = baseUrl.toHttpUrlOrNull() ?: return null
            val cookie = byHost[key(url)]?.firstOrNull { it.name == SESSION_COOKIE } ?: return null
            return cookie.name + "=" + cookie.value
        }

        fun forget(baseUrl: String) {
            val url = baseUrl.toHttpUrlOrNull() ?: return
            byHost.remove(key(url))
        }
    }

    private val jar = HostCookieJar()

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(jar)
        .build()

    /** Short fuse, for deciding which of several addresses is the live one. */
    private val prober = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** No read timeout: the event stream is silent by design between events. */
    private val streamer = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Find an address that answers, make sure we are signed in on it, and say
     * what happened in words a person can act on.
     */
    suspend fun connect(server: Server): Connection = withContext(Dispatchers.IO) {
        if (server.urls.isEmpty()) return@withContext Connection.Unreachable("No address configured.")

        var lastFailure: String? = null
        for (base in server.candidates()) {
            val status = readStatus(base) { lastFailure = it } ?: continue

            store.rememberGoodUrl(server.id, base)

            if (!status.remoteAccessEnabled) {
                return@withContext Connection.Refused(
                    base,
                    "Remote access is off",
                    "Remote access is turned off on that server. It can only be switched on there, in its Settings.",
                )
            }
            if (!status.configured) {
                return@withContext Connection.Refused(
                    base,
                    "No password set there",
                    "That server has no username and password. They can only be set on the machine itself.",
                )
            }
            if (status.authenticated) return@withContext Connection.Ready(base)

            return@withContext login(server, base)
        }
        Connection.Unreachable(lastFailure ?: "No address answered.")
    }

    private fun readStatus(base: String, onFailure: (String) -> Unit): AuthStatus? = try {
        prober.newCall(getRequest(base, "/api/auth/status")).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                onFailure(base + " answered HTTP " + response.code)
                null
            } else {
                json.decodeFromString<AuthStatus>(body)
            }
        }
    } catch (e: IOException) {
        onFailure(describe(base, e))
        null
    } catch (_: Exception) {
        onFailure(base + " did not answer like claude-history")
        null
    }

    private fun login(server: Server, base: String): Connection {
        val payload = json.encodeToString(LoginRequest(server.username, server.password))
        val origin = base.trimEnd('/')
        val request = Request.Builder()
            .url(origin + "/api/auth/login")
            // The trap with no equivalent in a browser: without this header the
            // server refuses the POST outright, from every machine but its own.
            .header("Origin", origin)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) return Connection.Ready(base)
                val error = runCatching { json.decodeFromString<ApiError>(body) }.getOrNull()
                val short = when (response.code) {
                    401 -> "Wrong username or password"
                    429 -> "Too many attempts"
                    403 -> "Refused"
                    else -> "HTTP " + response.code
                }
                Connection.Refused(
                    base,
                    short,
                    error?.error ?: ("The server refused the sign-in (HTTP " + response.code + ")."),
                    error?.retryAfterSeconds,
                )
            }
        } catch (e: IOException) {
            Connection.Unreachable(describe(base, e))
        }
    }

    /**
     * An authenticated GET, signing in again once if the session has gone. What
     * everything that READS a server goes through, as opposed to merely reaching
     * one.
     */
    suspend fun getText(server: Server, base: String, path: String): String? =
        withContext(Dispatchers.IO) {
            try {
                var result = call(base, path)
                if (result.first == 401) {
                    jar.forget(base)
                    if (login(server, base) !is Connection.Ready) return@withContext null
                    result = call(base, path)
                }
                if (result.first == 200) result.second else null
            } catch (_: IOException) {
                null
            }
        }

    private fun call(base: String, path: String): Pair<Int, String?> =
        http.newCall(getRequest(base, path)).execute().use { it.code to it.body.string() }

    /** The bell, whole: what has stopped and is still open, newest first. */
    suspend fun notifications(server: Server, base: String): List<StoppedRow>? {
        val body = getText(server, base, "/api/notifications") ?: return null
        return runCatching { json.decodeFromString<StoppedList>(body).stopped }.getOrNull()
    }

    /** The server's own notification preferences, which ours inherit. */
    suspend fun serverSettings(server: Server, base: String): ServerSettings? {
        val body = getText(server, base, "/api/settings") ?: return null
        return runCatching { json.decodeFromString<ServerSettings>(body) }.getOrNull()
    }

    /**
     * Follow the server's event stream until it ends or the caller is cancelled,
     * handing over the `type` of every event that arrives.
     *
     * Parsed by hand rather than with a library: the format is `data: {...}` and
     * a blank line, and the whole reason this connection exists is one event out
     * of a dozen. The read timeout has to be zero -- the stream is silent by
     * design between things happening, with a heartbeat every 25 seconds that is
     * also what keeps a NAT from dropping it.
     */
    suspend fun streamEvents(base: String, onEvent: (String) -> Unit) = withContext(Dispatchers.IO) {
        val call = streamer.newCall(getRequest(base, "/api/events"))
        // A blocking read does not notice cancellation; closing the call is what
        // makes it return.
        coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return@use
                val source = response.body.source()
                while (isActive && !source.exhausted()) {
                    val line = source.readUtf8LineStrict()
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    val type = runCatching {
                        json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.content
                    }.getOrNull()
                    if (type != null) onEvent(type)
                }
            }
        } catch (_: IOException) {
            // The stream ending is the normal way out of here, not an error:
            // the caller reconnects.
        } catch (_: IllegalStateException) {
        }
    }

    /**
     * The session cookie, for the WebView. This is the whole reason the native
     * side signs in at all: one login, and the embedded browser never sees a
     * login page.
     */
    fun sessionCookie(baseUrl: String): String? = jar.sessionCookie(baseUrl)

    private fun getRequest(base: String, path: String) = Request.Builder()
        .url(base.trimEnd('/') + path)
        .get()
        .build()

    private fun describe(base: String, e: IOException): String {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unknown error"
        return base + " is not answering (" + reason + ")"
    }

    companion object {
        const val SESSION_COOKIE = "ch_session"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** What reaching a server came to, in a form the UI can draw and a person can act on. */
sealed interface Connection {
    /** Signed in, on this address. */
    data class Ready(val baseUrl: String) : Connection

    /** The server answered and said no. [short] is the chip, [detail] the sentence. */
    data class Refused(
        val baseUrl: String,
        val short: String,
        val detail: String,
        val retryAfterSeconds: Int? = null,
    ) : Connection

    /** Nothing answered. Usually means the phone is not on that network. */
    data class Unreachable(val detail: String) : Connection
}
