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
