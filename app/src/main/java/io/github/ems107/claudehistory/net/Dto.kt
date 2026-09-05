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
 * What the session said as it stopped: the pending call, the plan, the question
 * it asked, or the last thing it answered.
 *
 * Without it a permission and a finished turn read identically on the phone,
 * and the only way to find out whether a notification mattered was to open it.
 *
 * `text` arrives already cut by the server, with `chars` beside it saying how
 * long the real thing was -- a quote that stops mid-word without saying so
 * reads as a bug in the app rather than as a long answer.
 */
@Serializable
data class StopPreview(
    /** `tool` | `plan` | `question` | `answer` | `error`. */
    val kind: String = "",
    /** The tool's name, the plan's title, the question. Null when the text is its own headline. */
    val label: String? = null,
    val text: String = "",
    /** The real length, before the cut. */
    val chars: Int = 0,
    val truncated: Boolean = false,
)

/**
 * The one preview kind whose text is its own headline, and the only one this app
 * has to tell apart. It lives here with the shape it describes, like every other
 * value that comes off the wire.
 */
const val PREVIEW_ERROR = "error"

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
    /**
     * **Null is a real answer, and it arrives late.** The server raises the row
     * the instant the session stops and reads the transcript a beat afterwards,
     * so the same row comes back a second time -- same `at` -- now carrying its
     * quote. It stays null for good when there was nothing to quote, and on any
     * server older than the field.
     */
    val preview: StopPreview? = null,
)

@Serializable
data class StoppedList(val stopped: List<StoppedRow> = emptyList())

/**
 * One Claude Code process that is alive on that machine right now, cut down to
 * what it takes to count it.
 *
 * This is a STATE, unlike the bell, which is a memory of transitions: every
 * open terminal is in here, resting, and that is the point -- "what is going on
 * over there" is a question the bell cannot answer.
 */
@Serializable
data class LiveRow(
    val sessionId: String = "",
    val status: String = "",
    val waitingFor: String? = null,
)

/** Answering. */
const val LIVE_BUSY = "busy"

/** A dialog is on screen -- a permission, a question, a plan to approve. */
const val LIVE_WAITING = "waiting"

/**
 * At rest. Two values for one state: `shell` is `idle` with a shell open on top
 * of it, and nothing about the conversation differs.
 */
val LIVE_STOPPED = setOf("idle", "shell")

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
