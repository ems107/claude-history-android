package io.github.ems107.claudehistory.update

import android.content.Context
import android.util.Log
import io.github.ems107.claudehistory.BuildConfig
import io.github.ems107.claudehistory.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The app updating itself, the way claude-history does: read the GitHub
 * releases, verify the SHA-256 of the APK, hand it to the package installer.
 *
 * Two rules carried over from that project because they were paid for there:
 *
 * - **The check is a conditional GET.** `If-None-Match` means an unchanged
 *   answer is a free 304, which matters against a rate limit of sixty an hour
 *   with no credentials.
 * - **Nothing is downloaded or installed without being asked for.** The check
 *   only ever says a version exists.
 *
 * And one that is specific to Android: an update installs over the app **only
 * if it carries the same signature**, so a release signed with a different key
 * does not upgrade, it collides. That is why the keystore is backed up rather
 * than regenerated.
 */
object Updates {

    private const val TAG = "claude-history"
    private const val REPO = "ems107/claude-history-android"

    /** Once a day is plenty for a personal tool nobody is waiting on. */
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** What the app is running now, so the UI need not know where it lives. */
    val currentVersion: String = BuildConfig.VERSION_NAME

    /**
     * Ask GitHub whether there is anything newer. [automatic] is the once-a-day
     * one and obeys both the switch and the interval; a check the user asked for
     * obeys neither.
     */
    suspend fun check(context: Context, automatic: Boolean) = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        if (automatic) {
            if (!prefs.autoUpdateCheck) return@withContext
            if (System.currentTimeMillis() - prefs.lastCheckAt < CHECK_INTERVAL_MS) return@withContext
        }
        if (_state.value is UpdateState.Downloading) return@withContext

        _state.value = UpdateState.Checking
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases?per_page=20")
                .header("Accept", "application/vnd.github+json")
                // Only the automatic check sends the ETag. A 304 saves a rate
                // limit but answers with no download links, and a person who
                // pressed the button wants the links.
                .apply { if (automatic) prefs.releasesEtag?.let { header("If-None-Match", it) } }
                .build()

            http.newCall(request).execute().use { response ->
                prefs.lastCheckAt = System.currentTimeMillis()

                if (response.code == 304) {
                    _state.value = verdictFor(prefs.knownLatest)
                    return@use
                }
                if (!response.isSuccessful) {
                    _state.value = UpdateState.Failed("GitHub answered HTTP ${response.code}.")
                    return@use
                }
                response.header("ETag")?.let { prefs.releasesEtag = it }

                val releases = json.decodeFromString<List<GhRelease>>(response.body.string())
                val newest = releases
                    .filter { !it.draft && !it.prerelease && versionOf(it.tag) != null }
                    .maxByOrNull { Version(versionOf(it.tag)!!) }

                if (newest == null) {
                    prefs.knownLatest = null
                    _state.value = UpdateState.UpToDate
                    return@use
                }
                val version = versionOf(newest.tag)!!
                prefs.knownLatest = version
                _state.value = if (Version(version) > Version(currentVersion)) {
                    UpdateState.Available(newest.toRelease(version))
                } else {
                    UpdateState.UpToDate
                }
            }
        } catch (e: IOException) {
            _state.value = UpdateState.Failed(e.message ?: "Could not reach GitHub.")
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("GitHub answered something unexpected.")
            Log.w(TAG, "update check failed", e)
        }
    }

    /**
     * Download, verify and hand over. The verification is not a formality: this
     * is an APK about to be installed with the app's own signature, and a
     * truncated download is far more likely than a malicious one.
     */
    suspend fun download(context: Context, release: Release): File? = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Downloading(release, 0)
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, release.assetName)

            http.newCall(Request.Builder().url(release.assetUrl).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    _state.value = UpdateState.Failed("The download answered HTTP ${response.code}.")
                    return@withContext null
                }
                val total = release.sizeBytes.takeIf { it > 0 } ?: response.body.contentLength()
                var moved = 0L
                response.body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            moved += read
                            if (total > 0) {
                                _state.value = UpdateState.Downloading(
                                    release,
                                    ((moved * 100) / total).toInt().coerceIn(0, 100),
                                )
                            }
                        }
                    }
                }
            }

            _state.value = UpdateState.Verifying(release)
            val expected = expectedSha(release) ?: run {
                _state.value = UpdateState.Failed("The release has no checksum for ${release.assetName}.")
                return@withContext null
            }
            val actual = sha256(file)
            if (!actual.equals(expected, ignoreCase = true)) {
                file.delete()
                _state.value = UpdateState.Failed("The download did not match its checksum.")
                return@withContext null
            }
            file
        } catch (e: IOException) {
            _state.value = UpdateState.Failed(e.message ?: "The download failed.")
            null
        }
    }

    fun installing(release: Release) {
        _state.value = UpdateState.Installing(release)
    }

    fun failed(message: String) {
        _state.value = UpdateState.Failed(message)
    }

    fun idle() {
        _state.value = UpdateState.Idle
    }

    private fun verdictFor(known: String?): UpdateState {
        if (known == null) return UpdateState.UpToDate
        // A 304 means the list is unchanged, and the list is where the download
        // links live -- so a version known to be newer is reported as such, and
        // asking for it re-reads the list without the ETag.
        return if (Version(known) > Version(currentVersion)) UpdateState.Stale(known) else UpdateState.UpToDate
    }

    private fun expectedSha(release: Release): String? {
        val url = release.checksumsUrl ?: return null
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body.string()
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.endsWith(release.assetName) }
                    ?.substringBefore(' ')
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun versionOf(tag: String): String? =
        Regex("""^v?(\d+\.\d+\.\d+)$""").find(tag.trim())?.groupValues?.get(1)
}

/** A release, reduced to what installing one needs. */
data class Release(
    val version: String,
    val notes: String,
    val assetName: String,
    val assetUrl: String,
    val checksumsUrl: String?,
    val sizeBytes: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState

    /** Newer, and ready to install. */
    data class Available(val release: Release) : UpdateState

    /**
     * Newer, but only the version number is known -- the answer was a 304 and
     * the links were not in it. Asking again fetches them.
     */
    data class Stale(val version: String) : UpdateState

    data class Downloading(val release: Release, val percent: Int) : UpdateState
    data class Verifying(val release: Release) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Numeric semver, so 0.10.0 beats 0.9.0. */
private class Version(text: String) : Comparable<Version> {
    private val parts = text.split('.').map { it.toIntOrNull() ?: 0 }

    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val mine = parts.getOrElse(i) { 0 }
            val theirs = other.parts.getOrElse(i) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return 0
    }
}

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tag: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
) {
    fun toRelease(version: String): Release {
        val apk = assets.firstOrNull { it.name.startsWith("claude-history-android-") && it.name.endsWith(".apk") }
        val sums = assets.firstOrNull { it.name == "checksums.txt" }
        return Release(
            version = version,
            notes = withoutTitleLine(body?.trim().orEmpty(), version),
            assetName = apk?.name.orEmpty(),
            assetUrl = apk?.url.orEmpty(),
            checksumsUrl = sums?.url,
            sizeBytes = apk?.size ?: 0,
        )
    }
}

/**
 * Drop a first line that is only the version.
 *
 * The notes are the annotated tag's whole message, whose first line is its
 * subject — so a tag written the ordinary way opens with `v0.1.1`, directly
 * under a panel already saying "Version 0.1.1 is available". Said twice, one of
 * them is noise.
 */
private fun withoutTitleLine(notes: String, version: String): String {
    val first = notes.lineSequence().firstOrNull()?.trim() ?: return notes
    if (first != version && first != "v$version") return notes
    return notes.substringAfter('\n', "").trim()
}

@Serializable
private data class GhAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
)
