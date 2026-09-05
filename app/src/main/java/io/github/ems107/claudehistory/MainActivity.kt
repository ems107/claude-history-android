package io.github.ems107.claudehistory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ems107.claudehistory.ui.ServerEditScreen
import io.github.ems107.claudehistory.ui.ServerListScreen
import io.github.ems107.claudehistory.ui.ServersViewModel
import io.github.ems107.claudehistory.ui.ViewerScreen
import io.github.ems107.claudehistory.ui.WebViewCache

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebViewCache.enableDebugging()
        setContent {
            AppTheme { App() }
        }
    }

    override fun onDestroy() {
        // Only when the app is really going away: the manifest declares the
        // configuration changes this Activity handles itself, so this is not a
        // rotation and the cached WebViews have nothing left to be shown in.
        if (isFinishing) WebViewCache.releaseAll()
        super.onDestroy()
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
}

@Composable
private fun App() {
    val viewModel: ServersViewModel = viewModel()
    var screen by remember { mutableStateOf<Screen>(Screen.Servers) }

    BackHandler(enabled = screen !is Screen.Servers) { screen = Screen.Servers }

    when (val current = screen) {
        is Screen.Servers -> {
            LaunchedEffect(Unit) { viewModel.refreshAll() }
            ServerListScreen(
                viewModel = viewModel,
                onOpen = { screen = Screen.Viewer(it.id) },
                onEdit = { screen = Screen.Edit(it) },
                onAdd = { screen = Screen.Edit(null) },
            )
        }

        is Screen.Edit -> ServerEditScreen(
            viewModel = viewModel,
            serverId = current.id,
            onDone = { screen = Screen.Servers },
        )

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
