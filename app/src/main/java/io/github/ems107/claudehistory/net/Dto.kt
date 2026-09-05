package io.github.ems107.claudehistory.net

import kotlinx.serialization.Serializable

/**
 * The shapes claude-history answers with, cut down to what this app reads.
 * `ignoreUnknownKeys` is on, so the server growing a field never breaks us --
 * and every one of these is documented in docs/AI_SERVER_CONTRACT.md.
 */

@Serializable
data class AuthStatus(
    val remote: Boolean = false,
    val remoteAccessEnabled: Boolean = false,
    val configured: Boolean = false,
    val authenticated: Boolean = false,
)

@Serializable
data class Meta(
    val version: String = "",
    val devInstance: Boolean = false,
    val sessionCount: Int = 0,
    val projectCount: Int = 0,
)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class ApiError(val error: String? = null, val retryAfterSeconds: Int? = null)

/**
 * One session that has stopped: a row of the bell, and everything a
 * notification needs to be drawn without a second request.
 *
 * `at` is epoch milliseconds, unlike the ISO strings elsewhere in that API, and
 * it is what tells a NEW stop from the same one still standing.
 */
@Serializable
data class StoppedRow(
    val sessionId: String = "",
    /** `needs-you` or `finished`. */
    val kind: String = "",
    /** The CLI's own words -- "permission prompt", "input needed". Null when finished. */
    val waitingFor: String? = null,
    val at: Long = 0,
    val source: String = "cli",
    val title: String? = null,
    val projectName: String? = null,
    val projectKey: String? = null,
    val cwd: String? = null,
    val stillOpen: Boolean = true,
)

@Serializable
data class StoppedList(val stopped: List<StoppedRow> = emptyList())

/**
 * The server's own notification preferences, which this app obeys by default.
 * Nothing on the server reads them -- they are for its own browser -- but they
 * are the closest thing there is to "what this person wants to be told about",
 * and ignoring them by default would be the app deciding it knows better.
 */
@Serializable
data class ServerSettings(
    val notifyEnabled: Boolean = true,
    val notifyOnNeedsYou: Boolean = true,
    val notifyOnFinished: Boolean = true,
)

const val KIND_NEEDS_YOU = "needs-you"
const val KIND_FINISHED = "finished"
