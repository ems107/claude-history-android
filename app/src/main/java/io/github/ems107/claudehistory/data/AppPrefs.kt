package io.github.ems107.claudehistory.data

import android.content.Context

/**
 * The handful of settings that are about the app rather than about a server.
 *
 * Plain SharedPreferences: there are four values, they are read once at startup
 * and written when a switch moves, and nothing here is worth a database or a
 * dependency.
 */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    /**
     * The only automatic network call this app makes, and the switch that turns
     * it off. On by default because an app that installs itself is no use if it
     * never says a new version exists -- and named in the README, which is the
     * price of any automatic call at all.
     */
    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    /** GitHub's ETag, so an unchanged answer costs a 304 and no rate limit. */
    var releasesEtag: String?
        get() = prefs.getString(KEY_ETAG, null)
        set(value) = prefs.edit().putString(KEY_ETAG, value).apply()

    var lastCheckAt: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    /** The newest version GitHub reported, remembered across a 304. */
    var knownLatest: String?
        get() = prefs.getString(KEY_KNOWN_LATEST, null)
        set(value) = prefs.edit().putString(KEY_KNOWN_LATEST, value).apply()

    private companion object {
        const val KEY_AUTO_UPDATE = "autoUpdateCheck"
        const val KEY_ETAG = "releasesEtag"
        const val KEY_LAST_CHECK = "lastCheckAt"
        const val KEY_KNOWN_LATEST = "knownLatest"
    }
}
