package io.github.ems107.claudehistory

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ems107.claudehistory.notify.Notifications
import io.github.ems107.claudehistory.notify.WatchService
import io.github.ems107.claudehistory.update.Updates
import io.github.ems107.claudehistory.ui.AppSettingsScreen
import io.github.ems107.claudehistory.ui.ServerEditScreen
import io.github.ems107.claudehistory.ui.ServerListScreen
import io.github.ems107.claudehistory.ui.ServersViewModel
import io.github.ems107.claudehistory.ui.ViewerScreen
import io.github.ems107.claudehistory.ui.WebViewCache
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebViewCache.enableDebugging()
        Notifications.ensureChannels(this)
        consume(intent)
        setContent {
            AppTheme { App() }
        }
    }

    /** The activity is `singleTask`, so a tapped notification arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    /**
     * A notification was tapped: go to that session, and count it as seen.
     *
     * Opening it is the same act as swiping it away, as far as this phone is
     * concerned -- and it is more than that on the server, because the viewer
     * withdraws the bell's row on arrival, which clears the notification on
     * every other device too.
     */
    private fun consume(intent: Intent?) {
        val serverId = intent?.getStringExtra(Notifications.EXTRA_SERVER_ID) ?: return
        val sessionId = intent.getStringExtra(Notifications.EXTRA_SESSION_ID) ?: return
        intent.getStringExtra(Notifications.EXTRA_KEY)?.let { key ->
            (application as ClaudeHistoryApp).acknowledge(key, intent.getLongExtra(Notifications.EXTRA_AT, 0L))
        }
        target.value = Screen.Viewer(serverId, "/session/$sessionId")
    }

    override fun onDestroy() {
        // Only when the app is really going away: the manifest declares the
        // configuration changes this Activity handles itself, so this is not a
        // rotation and the cached WebViews have nothing left to be shown in.
        if (isFinishing) WebViewCache.releaseAll()
        super.onDestroy()
    }

    companion object {
        /**
         * Where a notification wants the app to be. A flow rather than a call
         * because the intent arrives before -- or long after -- the composable
         * that has to act on it exists.
         */
        val target = MutableStateFlow<Screen?>(null)
    }
}

/**
 * Three screens and one rule -- back goes to the list -- so there is no
 * navigation library here. The viewer registers its own back handler, which
 * wins over this one, and that is what makes back walk the page history first.
 */
sealed interface Screen {
    data object Servers : Screen

    /** `null` is a server that does not exist yet. */
    data class Edit(val id: String?) : Screen

    data class Viewer(val serverId: String, val path: String = "/") : Screen

    data object Settings : Screen
}

@Composable
private fun App() {
    val viewModel: ServersViewModel = viewModel()
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Servers) }
    val servers by viewModel.servers.collectAsState()
    val target by MainActivity.target.collectAsState()

    // A tapped notification wins over wherever the app happened to be.
    LaunchedEffect(target) {
        target?.let {
            screen = it
            MainActivity.target.value = null
        }
    }

    // Nothing to watch is nothing to run: the service starts with the first
    // server and stops itself when the last one goes.
    LaunchedEffect(servers.isEmpty()) {
        if (servers.isNotEmpty()) WatchService.start(context)
    }

    // The one automatic network call, once a day, and only if it is switched on.
    LaunchedEffect(Unit) { Updates.check(context, automatic = true) }

    BackHandler(enabled = screen !is Screen.Servers) { screen = Screen.Servers }

    when (val current = screen) {
        is Screen.Servers -> {
            LaunchedEffect(Unit) { viewModel.refreshAll() }
            ServerListScreen(
                viewModel = viewModel,
                onOpen = { screen = Screen.Viewer(it.id) },
                onEdit = { screen = Screen.Edit(it) },
                onAdd = { screen = Screen.Edit(null) },
                onSettings = { screen = Screen.Settings },
            )
        }

        is Screen.Edit -> ServerEditScreen(
            viewModel = viewModel,
            serverId = current.id,
            onDone = { screen = Screen.Servers },
        )

        is Screen.Settings -> AppSettingsScreen(onBack = { screen = Screen.Servers })

        is Screen.Viewer -> ViewerScreen(
            viewModel = viewModel,
            serverId = current.serverId,
            startPath = current.path,
            onBack = { screen = Screen.Servers },
        )
    }
}

/**
 * The whole theme, and it stays this small on purpose: the palette is claude-
 * history's own ground and its orange, and every screen in this app is either a
 * list or a WebView showing the server's own colours.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkScheme else LightScheme
    MaterialTheme(colorScheme = colors) {
        Surface(color = colors.background, content = content)
    }
}
