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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ems107.claudehistory.data.Server
import io.github.ems107.claudehistory.net.Connection

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
    busy: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    server.label(),
                    style = MaterialTheme.typography.titleMedium,
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
            Box(Modifier.padding(top = 6.dp)) { StateLine(state, busy) }
        }
    }
}

@Composable
private fun StateLine(state: Connection?, busy: Boolean) {
    val (text, colour) = when {
        busy && state == null -> "Checking..." to MaterialTheme.colorScheme.onSurfaceVariant
        state == null -> "Not checked yet" to MaterialTheme.colorScheme.onSurfaceVariant
        state is Connection.Ready -> "Signed in" to Ok
        state is Connection.Refused -> state.short to MaterialTheme.colorScheme.error
        state is Connection.Unreachable -> "Not reachable" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = colour)
}

private val Ok = Color(0xFF2E7D32)
