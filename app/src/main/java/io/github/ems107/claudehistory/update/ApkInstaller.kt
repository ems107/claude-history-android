package io.github.ems107.claudehistory.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Handing an APK to Android.
 *
 * Two things have to be true before this can work at all, and both are the
 * user's to grant: the app must be allowed to install unknown apps, and the new
 * APK must carry **the same signature** as the installed one -- a different key
 * is not an upgrade, it is a collision, and the only way out of it is
 * uninstalling by hand.
 */
object ApkInstaller {

    private const val TAG = "claude-history"

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** The screen where that permission is granted, for this app alone. */
    fun permissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
        )

    /**
     * Start the install. Android takes over from here: it shows its own
     * confirmation, and the app is replaced -- and killed -- if the user agrees.
     */
    fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        try {
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("apk", 0, file.length()).use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val intent = Intent(context, InstallReceiver::class.java)
                    .setAction(InstallReceiver.ACTION)
                // MUTABLE on purpose: the package installer fills this intent in
                // with its status and, when it needs one, the confirmation to
                // show. An immutable one arrives empty and the install stalls
                // with no dialog and no error.
                val flags = if (Build.VERSION.SDK_INT >= 31) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "install failed", e)
            Updates.failed(e.message ?: "Android refused the install.")
        }
    }
}

/**
 * Where the package installer reports back. Its first answer is normally
 * "ask the user", which is a dialog we have to start ourselves.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { confirm?.let { context.startActivity(it) } }
            }

            PackageInstaller.STATUS_SUCCESS -> Updates.idle()

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Updates.failed(message ?: "The install did not complete.")
            }
        }
    }

    companion object {
        const val ACTION = "io.github.ems107.claudehistory.INSTALL_RESULT"
    }
}
