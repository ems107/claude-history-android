package io.github.ems107.claudehistory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.ems107.claudehistory.Ok
import io.github.ems107.claudehistory.Waiting
import io.github.ems107.claudehistory.data.Server
import io.github.ems107.claudehistory.net.Connection
import io.github.ems107.claudehistory.notify.LiveCounts
import io.github.ems107.claudehistory.notify.LiveKind
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

    val listState = rememberLazyListState()
    val reorder = remember(listState) { Reorder(listState) }
    val haptics = LocalHapticFeedback.current
    // What is drawn while a finger is down is the local copy, so a write from the
    // store mid-gesture cannot reorder the card under the finger.
    val shown = reorder.order ?: servers

    // Dragging to the edge of a list taller than the screen has to bring the rest
    // of it into view, or the last server cannot be reached by hand. What is
    // scrolled is added back to the offset, so the card stays under the finger.
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    LaunchedEffect(reorder.dragging) {
        if (reorder.dragging == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val step = reorder.edgeScrollStep(edge)
            if (step != 0f) reorder.scrolled(listState.scrollBy(step))
        }
    }

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
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Keyed, because the drag has to tell this one apart from a card.
                item(key = BANNER) { PermissionsBanner() }
                items(shown, key = { it.id }) { server ->
                    val lifted = reorder.dragging == server.id
                    ServerCard(
                        server = server,
                        state = states[server.id],
                        live = live[server.id],
                        busy = server.id in connecting,
                        lifted = lifted,
                        onOpen = { onOpen(server) },
                        onEdit = { onEdit(server.id) },
                        modifier = Modifier
                            .then(if (lifted) Modifier.zIndex(1f) else Modifier.animateItem())
                            .graphicsLayer {
                                if (lifted) {
                                    translationY = reorder.offset
                                    scaleX = LIFT
                                    scaleY = LIFT
                                }
                            }
                            .pointerInput(server.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        reorder.start(server.id, servers)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        reorder.drag(amount.y)
                                    },
                                    onDragEnd = { reorder.drop(viewModel::reorder) },
                                    onDragCancel = { reorder.drop(viewModel::reorder) },
                                )
                            },
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
    lifted: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        // A disabled server is drawn and can be edited, and that is all: the card
        // stops being a way in, so the Edit button is the only one left. It is
        // still draggable: parking a server says nothing about where it belongs.
        modifier = modifier.fillMaxWidth().clickable(enabled = server.enabled, onClick = onOpen),
        colors = if (server.enabled) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        },
        // The lift is the Card's own, so the shadow follows its rounded corners
        // instead of being a rectangle behind them.
        elevation = CardDefaults.cardElevation(
            defaultElevation = when {
                lifted -> 8.dp
                server.enabled -> 1.dp
                else -> 0.dp
            },
        ),
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
            // Nothing at all rather than three zeros: a count you have to read
            // before you can ignore it is worse than a line that is not there.
            //
            // And nothing at all for a server that is off, rather than waiting
            // for the service to drop its live state: that takes a moment, and
            // for that moment the card reads "Disabled" over a count of what is
            // running, which is two answers to the same question.
            val counts = live?.counts?.takeIf { server.enabled }
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
 * What is alive over there, in the same words the session list uses for the same
 * states -- the person reading this reads that too. `finished` is the exception,
 * because the web has no such state: it is the idle ones the bell is still
 * holding, and it is green because it is the one that is good news.
 *
 * The fragments are [LiveCounts.parts]' rather than this screen's, because the
 * permanent notice draws the same list joined with commas. All this adds is the
 * colour, which is the one thing a notification cannot have.
 */
@Composable
private fun CountsLine(counts: LiveCounts, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        counts.parts().forEachIndexed { index, (kind, text) ->
            if (index > 0) Dot()
            val colour = when (kind) {
                LiveKind.WAITING -> Waiting
                LiveKind.WORKING -> MaterialTheme.colorScheme.primary
                LiveKind.FINISHED -> Ok
                LiveKind.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            }
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

/** The banner's key, so the drag can tell it apart from a server's card. */
private const val BANNER = "permissions"

/** How much a picked-up card grows, which is the whole "it is in your hand". */
private const val LIFT = 1.02f

/** Pixels per frame at the edge of the list. A speed, not a distance. */
private const val EDGE_SPEED = 14f

/**
 * Picking a card up and putting it down somewhere else.
 *
 * Written by hand rather than pulled in. A reorderable list is sixty lines, and
 * a dependency here would be the fourth thing this app deliberately does not
 * have -- it spends them on what it could not write, and this is not that.
 *
 * Two things are worth knowing before changing any of it:
 *
 * **[order] is what is drawn while a finger is down**, and it is a local copy.
 * The store is written once, on release, because it rewrites the whole JSON
 * file on every mutation and a drag crosses a card every few frames.
 *
 * **A crossing is decided from the LAYOUT, not from the offset alone**, because
 * the cards are not all the same height -- the counts line comes and goes -- so
 * "one card down" is not a number. That is also why the offset is corrected on
 * every swap: without it the card jumps out from under the finger by the
 * difference between the two heights.
 */
private class Reorder(private val state: LazyListState) {

    var dragging by mutableStateOf<String?>(null)
        private set

    var offset by mutableFloatStateOf(0f)
        private set

    var order by mutableStateOf<List<Server>?>(null)
        private set

    fun start(id: String, servers: List<Server>) {
        dragging = id
        order = servers
        offset = 0f
    }

    fun drag(by: Float) {
        offset += by
        val id = dragging ?: return
        val list = order ?: return
        val visible = state.layoutInfo.visibleItemsInfo
        // The layout has not caught up with the last swap yet -- more than one
        // touch event can arrive between two layout passes -- and deciding a
        // crossing from stale positions is how a card oscillates in place.
        val onScreen = visible.mapNotNull { it.key as? String }.filter { it != BANNER }
        val seen = onScreen.toSet()
        if (onScreen != list.map { it.id }.filter { it in seen }) return

        val held = visible.firstOrNull { it.key == id } ?: return
        val centre = held.offset + held.size / 2f + offset
        val target = visible.firstOrNull { item ->
            item.key != id && item.key != BANNER && centre.toInt() in item.offset..(item.offset + item.size)
        } ?: return

        val from = list.indexOfFirst { it.id == id }
        val to = list.indexOfFirst { it.id == target.key }
        if (from < 0 || to < 0) return
        order = list.toMutableList().apply { add(to, removeAt(from)) }

        // Where the card is about to be laid out. Going down it lands at the far
        // end of the card it passed, because everything between them moves up by
        // its own height; going up it simply takes that card's place.
        val landing = if (to > from) target.offset + target.size - held.size else target.offset
        offset += held.offset - landing
    }

    /** Told what a frame of edge scrolling actually moved, so the card follows. */
    fun scrolled(by: Float) {
        offset += by
    }

    fun edgeScrollStep(edge: Float): Float {
        val id = dragging ?: return 0f
        val info = state.layoutInfo
        val held = info.visibleItemsInfo.firstOrNull { it.key == id } ?: return 0f
        val top = held.offset + offset
        return when {
            top < info.viewportStartOffset + edge -> -EDGE_SPEED
            top + held.size > info.viewportEndOffset - edge -> EDGE_SPEED
            else -> 0f
        }
    }

    fun drop(commit: (List<String>) -> Unit) {
        order?.let { list -> commit(list.map { it.id }) }
        dragging = null
        order = null
        offset = 0f
    }
}
