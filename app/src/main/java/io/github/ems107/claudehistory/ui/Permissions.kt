package io.github.ems107.claudehistory.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * What Android has to be asked for before any of this works, asked for where it
 * is obvious why: at the top of the server list, and only while something is
 * actually missing.
 *
 * Two things, and the second is the one people are surprised by. Without the
 * notification permission nothing can be shown at all; without the battery
 * exemption the connection is dropped while the screen is off, and the stops
 * arrive late, in a batch, or in the morning.
 */
@Composable
fun PermissionsBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(notificationsAllowed(context)) }
    var batteryFree by remember { mutableStateOf(batteryUnrestricted(context)) }

    // Both are granted in another screen, so the answer is only ever fresh on
    // the way back into this one.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsOn = notificationsAllowed(context)
                batteryFree = batteryUnrestricted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsOn = granted || notificationsAllowed(context) }

    if (notificationsOn && batteryFree) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (notificationsOn || batteryFree) "One thing is missing" else "Two things are missing",
                style = MaterialTheme.typography.titleSmall,
            )

            if (!notificationsOn) {
                Text(
                    "Notifications are off for this app, so nothing can be shown at all.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        askNotifications.launch("android.permission.POST_NOTIFICATIONS")
                    } else {
                        context.startActivity(appNotificationSettings(context))
                    }
                }) { Text("Allow notifications") }
            }

            if (!batteryFree) {
                Text(
                    "Android may put this app to sleep while the screen is off, which drops the " +
                        "connection to your servers and delays every notification.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = {
                    runCatching { context.startActivity(batteryExemption(context)) }
                }) { Text("Let it run in the background") }
            }
        }
    }
}

private fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun batteryUnrestricted(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return true
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

@Suppress("BatteryLife")
private fun batteryExemption(context: Context) =
    Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:" + context.packageName),
    )

private fun appNotificationSettings(context: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
