package io.github.ems107.claudehistory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ems107.claudehistory.Ok
import io.github.ems107.claudehistory.Waiting
import io.github.ems107.claudehistory.data.Server
import io.github.ems107.claudehistory.net.Connection
import io.github.ems107.claudehistory.notify.LiveCounts
import io.github.ems107.claudehistory.notify.ServerLive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    viewModel: ServersViewModel,
    onOpen: (Server) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
) {
    val servers by viewModel.servers.collectAsState()
    val states by viewModel.states.collectAsState()
    val live by viewModel.live.collectAsState()
    val connecting by viewModel.connecting.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("claude-history") },
                actions = {
                    TextButton(onClick = { viewModel.refreshAll() }) { Text("Refresh") }
                    TextButton(onClick = onSettings) { Text("Settings") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Text("+", style = MaterialTheme.typography.headlineSmall) }
        },
    ) { padding ->
        if (servers.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { PermissionsBanner() }
                items(servers, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        state = states[server.id],
                        live = live[server.id],
                        busy = server.id in connecting,
                        onOpen = { onOpen(server) },
                        onEdit = { onEdit(server.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No servers yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add one with the + button: an address like http://192.168.1.10:7433, " +
                "and the username and password set on that machine.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ServerCard(
    server: Server,
    state: Connection?,
    live: ServerLive?,
    busy: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        // A disabled server is drawn and can be edited, and that is all: the card
        // stops being a way in, so the Edit button is the only one left.
        modifier = Modifier.fillMaxWidth().clickable(enabled = server.enabled, onClick = onOpen),
        colors = if (server.enabled) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        },
        elevation = CardDefaults.cardElevation(defaultElevation = if (server.enabled) 1.dp else 0.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    server.label(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (server.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                }
                TextButton(onClick = onEdit) { Text("Edit") }
            }
            Text(
                server.lastGoodUrl ?: server.urls.firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.padding(top = 6.dp)) { StateLine(server, state, live, busy) }
            val counts = live?.counts
            // Nothing at all rather than three zeros: a count you have to read
            // before you can ignore it is worse than a line that is not there.
            if (counts != null && counts.total > 0) {
                CountsLine(counts, Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * Whether the server answers, in the freshest terms available.
 *
 * The service wins whenever it has an opinion, because it is holding the
 * connection while this screen is only ever quoting one check from whenever it
 * was last opened -- which is how the card used to go on saying "Signed in" at
 * a server that had been off for an hour.
 */
@Composable
private fun StateLine(server: Server, state: Connection?, live: ServerLive?, busy: Boolean) {
    val (text, colour) = when {
        // First, because nothing else can be true of it: the service dropped its
        // live state when it was switched off, and `state` is whatever it last
        // answered before that.
        !server.enabled -> "Disabled" to MaterialTheme.colorScheme.onSurfaceVariant

        live != null -> live.connection to when {
            live.connected -> Ok
            live.refused -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        busy && state == null -> "Checking..." to MaterialTheme.colorScheme.onSurfaceVariant
        state == null -> "Not checked yet" to MaterialTheme.colorScheme.onSurfaceVariant
        state is Connection.Ready -> "Signed in" to Ok
        state is Connection.Refused -> state.short to MaterialTheme.colorScheme.error
        state is Connection.Unreachable -> "Not reachable" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = colour)
        // Said here rather than left to be deduced from a silent phone.
        if (live?.muted == true) {
            Dot()
            Text(
                "notifications off",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What is alive over there, in the same three words the session list uses for
 * the same three states -- the person reading this reads that too.
 */
@Composable
private fun CountsLine(counts: LiveCounts, modifier: Modifier = Modifier) {
    val parts = buildList {
        if (counts.waiting > 0) add("${counts.waiting} waiting" to Waiting)
        if (counts.working > 0) add("${counts.working} working" to MaterialTheme.colorScheme.primary)
        if (counts.idle > 0) add("${counts.idle} idle" to MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { index, (text, colour) ->
            if (index > 0) Dot()
            Text(text, style = MaterialTheme.typography.bodySmall, color = colour)
        }
    }
}

@Composable
private fun Dot() {
    Text(
        " · ",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}
