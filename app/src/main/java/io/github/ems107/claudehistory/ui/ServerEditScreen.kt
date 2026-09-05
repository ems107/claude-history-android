package io.github.ems107.claudehistory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ems107.claudehistory.data.Server
import io.github.ems107.claudehistory.net.Connection

/**
 * Adding or changing one server.
 *
 * The addresses are a plain multi-line field rather than a list widget, and the
 * order matters: it is the order they are tried in. One server, several ways to
 * reach it -- the LAN address at home, the tunnel address anywhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    viewModel: ServersViewModel,
    serverId: String?,
    onDone: () -> Unit,
) {
    val existing = remember(serverId) { serverId?.let { viewModel.serverOf(it) } }
    val isNew = existing == null

    var alias by remember { mutableStateOf(existing?.alias.orEmpty()) }
    var addresses by remember { mutableStateOf(existing?.urls?.joinToString("\n").orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var showPassword by remember { mutableStateOf(false) }

    val states by viewModel.states.collectAsState()
    val connecting by viewModel.connecting.collectAsState()
    val draftId = remember { existing?.id ?: Server().id }
    val state = states[draftId]
    val busy = draftId in connecting

    fun draft(): Server = Server(
        id = draftId,
        alias = alias.trim(),
        urls = normalizeAddresses(addresses),
        username = username.trim(),
        password = password,
        lastGoodUrl = existing?.lastGoodUrl,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Add a server" else "Edit server") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text("Name") },
                placeholder = { Text("Desktop") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = addresses,
                onValueChange = { addresses = it },
                label = { Text("Addresses, one per line, in the order to try") },
                placeholder = { Text("http://192.168.1.10:7433\nhttp://10.8.0.2:7433") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation =
                    if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.save(draft()) },
                    enabled = !busy && normalizeAddresses(addresses).isNotEmpty(),
                ) { Text(if (busy) "Testing..." else "Test connection") }

                Button(
                    onClick = {
                        viewModel.save(draft())
                        onDone()
                    },
                    enabled = normalizeAddresses(addresses).isNotEmpty(),
                ) { Text("Save") }
            }

            TestResult(state, busy)

            if (!isNew) {
                TextButton(
                    onClick = {
                        viewModel.delete(draftId)
                        onDone()
                    },
                ) { Text("Delete this server", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun TestResult(state: Connection?, busy: Boolean) {
    if (busy || state == null) return
    val (text, colour) = when (state) {
        is Connection.Ready -> "Signed in on ${state.baseUrl}" to MaterialTheme.colorScheme.primary
        is Connection.Refused -> state.detail to MaterialTheme.colorScheme.error
        is Connection.Unreachable -> state.detail to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = colour)
}

/**
 * One address per line, tidied: a bare host gets `http://`, because claude-
 * history has no HTTPS and typing it every time is a tax on the common case.
 */
fun normalizeAddresses(text: String): List<String> =
    text.lines()
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotEmpty() }
        .map { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
        .distinct()
