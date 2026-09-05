package io.github.ems107.claudehistory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.ems107.claudehistory.data.AppPrefs
import io.github.ems107.claudehistory.update.ApkInstaller
import io.github.ems107.claudehistory.update.Updates
import io.github.ems107.claudehistory.update.UpdateState
import kotlinx.coroutines.launch

/**
 * The app's own settings, which are almost entirely about updating itself.
 *
 * There is one automatic network call in this app and its switch is here, said
 * plainly rather than buried: what it does, where it goes, and that nothing is
 * ever downloaded or installed without being asked for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPrefs(context) }
    var auto by remember { mutableStateOf(prefs.autoUpdateCheck) }
    val state by Updates.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("claude-history ${Updates.currentVersion}", style = MaterialTheme.typography.titleMedium)
            Text(
                "The Android client. The version of each server is its own, and shows in its own header.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check for updates automatically", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Once a day, a small conditional request to api.github.com for this app's " +
                            "releases. It downloads nothing and installs nothing on its own, and it " +
                            "is the only thing this app does on the network by itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = auto,
                    onCheckedChange = {
                        auto = it
                        prefs.autoUpdateCheck = it
                    },
                )
            }

            UpdatePanel(state, onCheck = { scope.launch { Updates.check(context, automatic = false) } })

            if (!ApkInstaller.canInstall(context)) {
                Text(
                    "Android will not let this app install an update until you allow it to install " +
                        "unknown apps. Everything else works without it; only the update button does not.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(ApkInstaller.permissionIntent(context)) }
                }) { Text("Allow installing updates") }
            }
        }
    }
}

@Composable
private fun UpdatePanel(state: UpdateState, onCheck: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is UpdateState.Idle -> Text("Not checked yet.", style = MaterialTheme.typography.bodyMedium)
                is UpdateState.Checking -> Text("Checking…", style = MaterialTheme.typography.bodyMedium)
                is UpdateState.UpToDate -> Text(
                    "You are on the newest version.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                is UpdateState.Stale -> Text(
                    "Version ${state.version} is available. Check again to fetch it.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                is UpdateState.Available -> {
                    Text(
                        "Version ${state.release.version} is available.",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (state.release.notes.isNotBlank()) {
                        ReleaseNotes(state.release.notes)
                    }
                    Button(
                        enabled = state.release.assetUrl.isNotBlank(),
                        onClick = {
                            scope.launch {
                                val file = Updates.download(context, state.release) ?: return@launch
                                Updates.installing(state.release)
                                ApkInstaller.install(context, file)
                            }
                        },
                    ) { Text("Download and install") }
                }

                is UpdateState.Downloading -> {
                    Text("Downloading ${state.release.version}…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is UpdateState.Verifying -> Text(
                    "Checking the download…",
                    style = MaterialTheme.typography.bodyMedium,
                )

                is UpdateState.Installing -> Text(
                    "Android is installing ${state.release.version}.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                is UpdateState.Failed -> Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val busy = state is UpdateState.Checking ||
                state is UpdateState.Downloading ||
                state is UpdateState.Verifying
            OutlinedButton(onClick = onCheck, enabled = !busy) { Text("Check now") }
        }
    }
}

/**
 * The release notes, drawn rather than dumped.
 *
 * They are markdown written for the GitHub release page, so raw `###` and `**`
 * are the one thing on this screen a person actually reads arriving as syntax.
 * This is not a markdown renderer and does not pretend to be: headings, bullets,
 * bold and code spans are the whole of what these notes use.
 */
@Composable
private fun ReleaseNotes(notes: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        notes.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Spacer(Modifier.height(3.dp))

                line.startsWith("#") -> Text(
                    inlineMarkup(line.trimStart('#').trim()),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )

                line.startsWith("- ") || line.startsWith("* ") -> Row {
                    Text("•", style = MaterialTheme.typography.bodySmall)
                    Text(
                        inlineMarkup(line.drop(2)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }

                else -> Text(inlineMarkup(line), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val MARKUP = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")

private fun inlineMarkup(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    MARKUP.findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        val bold = match.groupValues[1]
        val code = match.groupValues[2]
        if (bold.isNotEmpty()) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        } else {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
        }
        last = match.range.last + 1
    }
    append(text.substring(last))
}
