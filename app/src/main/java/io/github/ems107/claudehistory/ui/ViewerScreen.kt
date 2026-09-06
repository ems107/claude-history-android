package io.github.ems107.claudehistory.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import io.github.ems107.claudehistory.BuildConfig
import io.github.ems107.claudehistory.R
import io.github.ems107.claudehistory.net.Connection
import kotlin.math.roundToInt

/**
 * claude-history itself, in an embedded browser that is already signed in.
 *
 * The page is the server's own, unchanged and not adapted to a phone: this
 * version is about reaching it at all. What the app adds is the one bar above
 * it, and the fact that no login screen ever appears -- the native side signed
 * in, and its cookie is handed to the WebView before the first load.
 */
@Composable
fun ViewerScreen(
    viewModel: ServersViewModel,
    serverId: String,
    startPath: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // From the flow rather than a snapshot, so renaming a server or switching it
    // off is seen by the bar instead of being frozen at whatever it said on the
    // way in.
    val servers by viewModel.servers.collectAsState()
    val server = servers.firstOrNull { it.id == serverId }
    val states by viewModel.states.collectAsState()
    val connecting by viewModel.connecting.collectAsState()
    val state = states[serverId]

    LaunchedEffect(serverId) {
        if (state !is Connection.Ready) viewModel.refresh(serverId)
    }

    // Belt and braces: a disabled server raises no notifications and its card is
    // not a way in, so nothing should arrive here -- but the rule is that it
    // cannot be opened, and this is where opening happens.
    val off = server != null && !server.enabled

    /** Back means back through the pages first, and out of the viewer last. */
    fun goBack() {
        val view = WebViewCache.get(serverId)
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    BackHandler { goBack() }

    // How the page is drawn, and it is reset every time the viewer is entered --
    // which costs nothing to arrange, because leaving destroys the view.
    var desktop by remember(serverId) { mutableStateOf(false) }
    var zoom by remember(serverId) { mutableIntStateOf(ZOOM_DEFAULT) }
    LaunchedEffect(serverId, desktop, zoom) { WebViewCache.setMode(serverId, desktop, zoom) }

    // Leaving the viewer -- by Servers, by walking back out of the page history,
    // or because a notification sends this screen to another server -- throws the
    // page away. Coming back to a server starts it again from the top, which is
    // what "Servers" reads as. Done on dispose because by then nothing is left to
    // re-read the generation and build a replacement nobody would see.
    DisposableEffect(serverId) { onDispose { WebViewCache.discard(serverId) } }

    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = server?.label() ?: "claude-history",
            progress = WebViewCache.progressOf(serverId),
            desktop = desktop,
            zoom = zoom,
            onHome = onBack,
            onDesktop = { desktop = !desktop },
            onZoom = { zoom = it },
            onReload = { WebViewCache.get(serverId)?.reload() },
        )

        OldWebViewWarning(context)

        // The page keeps clear of the gesture bar, of a cutout in landscape and of
        // the keyboard when a field inside the page takes focus: edge to edge
        // means the window no longer resizes for any of the three by itself.
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                ),
        ) {
            when (val current = if (off) null else state) {
                is Connection.Ready -> {
                    val cookie = viewModel.sessionCookie(current.baseUrl)
                    val target = current.baseUrl.trimEnd('/') + startPath
                    // Keyed on the generation so a view whose renderer died is
                    // replaced rather than shown dead.
                    key(WebViewCache.generationOf(serverId)) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx -> WebViewCache.obtain(ctx, serverId) },
                            update = { view ->
                                WebViewCache.load(view, serverId, current.baseUrl, target, cookie)
                            },
                        )
                    }
                }

                null -> if (off) {
                    CentredMessage("This server is disabled. Turn it back on in its settings.")
                } else {
                    CentredMessage("Connecting...", spinner = serverId in connecting)
                }
                is Connection.Refused -> CentredMessage(current.detail)
                is Connection.Unreachable -> CentredMessage(current.detail)
            }
        }
    }
}

/**
 * The one thing this app adds above the server's own page.
 *
 * There is no back button, deliberately: the phone already has one, and this
 * screen's own handler already makes it walk the page history first. What is
 * here instead is the four things a browser bar is for -- where you are, how
 * wide to draw it, how big, and load it again.
 */
@Composable
private fun ViewerBar(
    title: String,
    progress: Int,
    desktop: Boolean,
    zoom: Int,
    onHome: () -> Unit,
    onDesktop: () -> Unit,
    onZoom: (Int) -> Unit,
    onReload: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp) {
        Column {
            // Inside the Surface, the way Material3 does it in its own top bar: the
            // padding moves the buttons out from under the clock, and the Surface
            // still paints the strip they left behind. Outside it, the status bar
            // would sit on a band of bare window background.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    .height(52.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(onClick = onHome, contentPadding = PaddingValues(start = 6.dp, end = 10.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_chevron_left),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Servers", style = MaterialTheme.typography.labelLarge)
                }

                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )

                BarButton(
                    icon = R.drawable.ic_desktop,
                    description = if (desktop) "Desktop layout, on" else "Desktop layout, off",
                    // The state is shown rather than left to be inferred from the
                    // page: at 100% on a wide server both layouts look plausible.
                    active = desktop,
                    onClick = onDesktop,
                )

                ZoomPill(zoom, onZoom)

                BarButton(icon = R.drawable.ic_refresh, description = "Reload", onClick = onReload)
            }

            // Only while something is actually happening. A bar that is always
            // there, empty, is a line of furniture rather than an answer.
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .height(2.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
    }
}

@Composable
private fun BarButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = if (active) {
            IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

/**
 * Zoom out, the number, zoom in -- as one control, because they are one idea.
 * The number is what makes the buttons worth having over a pinch: it says where
 * you are, and it is the only thing that can, since the page zoom is ours.
 */
@Composable
private fun ZoomPill(zoom: Int, onZoom: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZoomStep("−", enabled = zoom > ZOOM_MIN) { onZoom((zoom - ZOOM_STEP).coerceAtLeast(ZOOM_MIN)) }
            Text(
                "$zoom%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(38.dp),
            )
            ZoomStep("+", enabled = zoom < ZOOM_MAX) { onZoom((zoom + ZOOM_STEP).coerceAtMost(ZOOM_MAX)) }
        }
    }
}

@Composable
private fun ZoomStep(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Text(
            glyph,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        )
    }
}

@Composable
private fun CentredMessage(text: String, spinner: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (spinner) CircularProgressIndicator(Modifier.padding(bottom = 16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * claude-history's interface is built with Tailwind v4, which needs Chrome 111
 * or newer. A rugged handheld can easily carry a WebView years older than that,
 * and the failure looks like a broken app rather than an old browser -- so it
 * says which it is.
 */
@Composable
private fun OldWebViewWarning(context: Context) {
    val major = remember {
        WebViewCompat.getCurrentWebViewPackage(context)
            ?.versionName
            ?.substringBefore('.')
            ?.toIntOrNull()
    }
    if (major == null || major >= MIN_CHROME) return
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            "This device's browser engine is Chrome $major. claude-history needs $MIN_CHROME " +
                "or newer to draw correctly -- update Android System WebView, or Chrome if it " +
                "is the provider.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(10.dp),
        )
    }
}

private const val MIN_CHROME = 111

/** The width "desktop" lays the page out at, whatever the phone's own is. */
private const val DESKTOP_WIDTH = 1280

/** Layout zoom, in per cent of the width the mode above would use on its own. */
private const val ZOOM_MIN = 30
private const val ZOOM_MAX = 300
private const val ZOOM_STEP = 10
private const val ZOOM_DEFAULT = 100

/**
 * How far a pinch is allowed to go, either way. Wide, because it is not this
 * app's business how close somebody wants to look.
 */
private const val PINCH_MIN = 0.05f
private const val PINCH_MAX = 10f

/**
 * How wide to lay the page out -- which is the only question the bar's zoom
 * asks, and the reason it is not a pinch.
 *
 * **The bar re-lays the page out; the pinch magnifies it. They are separate and
 * neither does the other's job.** Page zoom changes how many CSS pixels the
 * window is worth, so the page reflows at the new size and still fits across;
 * a pinch leaves the layout alone and makes a region bigger. Both are wanted,
 * and conflating them is how zooming in ends up meaning "now scroll sideways".
 *
 * So the width is always divided by the zoom and the scale is always whatever
 * makes that width fit the screen exactly. There is no second rule for desktop
 * mode: the switch only decides the width the page would be laid out at with
 * the zoom at 100.
 *
 * - **off** -- the phone's own width, so 100 % is precisely the
 *   `width=device-width, initial-scale=1` the page asks for.
 * - **on** -- 1280 px, so 100 % is the desktop layout scaled to fit.
 *
 * The consequence worth knowing: zooming in inside desktop mode narrows the
 * layout, and claude-history's own `md` breakpoint is 768 px, so somewhere past
 * 160 % the desktop layout gives way to the phone one at a larger size. That is
 * what reflowing MEANS, and a desktop browser does the same to a 1280 px window.
 * Reading the wide layout closer is the pinch's job, not this one's.
 *
 * It replaces the page's own tag rather than fighting it, because
 * `useWideViewPort` is only consulted when the page does not declare one.
 */
private fun viewportScript(base: Int, desktop: Boolean, zoom: Int): String {
    val factor = zoom / 100f
    val natural = if (desktop) DESKTOP_WIDTH else base
    val width = (natural / factor).roundToInt().coerceAtLeast(1)
    val scale = base.toFloat() / width
    // Pinned first, then freed a beat later. Chromium honours `initial-scale`
    // when the viewport description changes, but it will not pull a page back
    // from a scale the reader chose by hand -- and after a pinch that is exactly
    // where it is. Pinning both ends forces the new layout zoom; relaxing them
    // hands the pinch back, at the scale that was just asserted.
    //
    // The pending relax is cancelled first, and that is not tidiness: each
    // injection closes over ITS width and scale, so a timer from the previous
    // one firing after this one has run writes the old viewport back. Tapping
    // the zoom twice inside 50 ms is enough, and so is the bar being touched
    // while the page is still loading.
    return """
        (function () {
          var m = document.querySelector('meta[name="viewport"]');
          if (!m) { m = document.createElement('meta'); m.setAttribute('name', 'viewport'); document.head.appendChild(m); }
          if (window.__chFree) clearTimeout(window.__chFree);
          m.setAttribute('content', 'width=$width, initial-scale=$scale, minimum-scale=$scale, maximum-scale=$scale');
          window.__chFree = setTimeout(function () {
            m.setAttribute('content', 'width=$width, initial-scale=$scale, minimum-scale=$PINCH_MIN, maximum-scale=$PINCH_MAX');
          }, 50);
        })();
    """.trimIndent()
}

/** How the page is being drawn for one server, until the viewer is left. */
private data class ViewMode(val desktop: Boolean = false, val zoom: Int = ZOOM_DEFAULT)

/**
 * The WebView the viewer is showing, and the two things that outlive a
 * recomposition: what it has already loaded, and how it is being drawn.
 *
 * The view lives here rather than in the composable so that `AndroidView` can be
 * given the same one on every frame -- reloading on each recomposition would
 * throw away the scroll position and every fold the reader had opened. It does
 * NOT outlive the screen: leaving the viewer discards it, so coming back to a
 * server starts at the top rather than wherever you were, which is what the
 * Servers button reads as.
 */
object WebViewCache {
    private val views = mutableMapOf<String, WebView>()
    private val loaded = mutableMapOf<String, String>()
    private val homes = mutableMapOf<String, Uri>()
    private val modes = mutableMapOf<String, ViewMode>()

    /**
     * How far the page has loaded, 0..100. Compose state, because the bar draws
     * it: the rest of this object is read by code that is already recomposing
     * for another reason.
     *
     * Not called `progress`. Inside the `apply` block below, `WebView.progress`
     * is in scope and wins the name without a word being said about it.
     */
    private val loadProgress = mutableStateMapOf<String, Int>()

    fun progressOf(id: String): Int = loadProgress[id] ?: 100

    /**
     * Bumped when a view has to be thrown away. Compose state on purpose: the
     * screen keys its `AndroidView` on it, so a discarded view is not merely
     * forgotten here -- it is replaced on the next frame.
     */
    private val generations = mutableStateMapOf<String, Int>()

    fun generationOf(id: String): Int = generations[id] ?: 0

    fun get(id: String): WebView? = views[id]

    /** Destroy one view and arrange for a fresh one to take its place. */
    fun discard(id: String) {
        views.remove(id)?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        loaded.remove(id)
        modes.remove(id)
        loadProgress.remove(id)
        generations[id] = generationOf(id) + 1
    }

    /**
     * Draw this server's page at that width and that size, now and after every
     * load. Applied immediately as well as remembered, because changing the zoom
     * has to be visible without reloading -- a reload would lose the reader's
     * place, which is a strange price for a bigger typeface.
     */
    fun setMode(id: String, desktop: Boolean, zoom: Int) {
        modes[id] = ViewMode(desktop, zoom)
        views[id]?.let { applyMode(it, id) }
    }

    /**
     * The width the page has to work with, in CSS pixels.
     *
     * Measured off the view rather than asked of `window.screen`, which answers
     * for the whole display: in landscape, or beside a cutout, the page is
     * narrower than the screen and a viewport built from the wrong number lays
     * out too wide by exactly the insets. Before the first layout there is no
     * width to measure, so the screen is the fallback.
     */
    private fun applyMode(view: WebView, id: String) {
        val mode = modes[id] ?: return
        val density = view.resources.displayMetrics.density
        val base = (view.width / density).roundToInt()
            .takeIf { it > 0 }
            ?: (view.resources.displayMetrics.widthPixels / density).roundToInt()
        view.evaluateJavascript(viewportScript(base, mode.desktop, mode.zoom), null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(context: Context, id: String): WebView {
        views[id]?.let { existing ->
            // A reused view may still be attached to the layout it was in before
            // the last navigation, and reattaching it without this throws.
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val view = WebView(context).apply {
            // Not decoration, and it cost an hour: a WebView whose layout params
            // are WRAP_CONTENT gives the page a viewport with no defined height,
            // so every percentage height inside it resolves to zero. claude-
            // history is `html, body, #root { height: 100% }` and a column of
            // flex children below that, so the whole app collapsed to an
            // invisible strip under a perfectly drawn header.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Consulted only when the page declares no viewport of its own, which
            // claude-history does -- so what actually decides the layout is the
            // tag [viewportScript] writes over it.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // The pinch, which is the other half of the deal: the bar re-lays the
            // page out and this makes a piece of it bigger. Without the controls
            // enabled the meta tag's scale range is never offered to anybody.
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = ServerWebViewClient(id)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loadProgress[id] = newProgress
                }
            }
        }
        views[id] = view
        return view
    }

    /**
     * Load, but only when there is something new to load: this runs on every
     * recomposition, and reloading the page each time would throw away the
     * scroll position and every fold the reader had opened.
     */
    fun load(view: WebView, id: String, baseUrl: String, target: String, cookie: String?) {
        homes[id] = Uri.parse(baseUrl)
        if (loaded[id] == target) return
        loaded[id] = target

        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.setAcceptThirdPartyCookies(view, true)
        if (cookie == null) {
            view.loadUrl(target)
            return
        }
        // The cookie must be in place before the first request, and setCookie is
        // asynchronous -- loading without waiting lands on the login page.
        manager.setCookie(baseUrl, "$cookie; Path=/") {
            manager.flush()
            view.post { view.loadUrl(target) }
        }
    }

    /** Called when the Activity is really going away, not on a rotation. */
    fun releaseAll() {
        views.values.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        views.clear()
        loaded.clear()
        homes.clear()
        modes.clear()
        loadProgress.clear()
    }

    fun enableDebugging() {
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }

    private class ServerWebViewClient(private val id: String) : WebViewClient() {
        /**
         * The renderer died -- almost always out of memory, and these views are
         * kept alive for hours holding long conversations. Returning false from
         * here takes the whole app down with it; returning true lets us throw the
         * dead view away and build another, which is what the generation counter
         * is for.
         */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            discard(id)
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            applyMode(view, id)
            // Not cosmetic: claude-history withdraws a session's row from the
            // bell only while the page is visible AND has the focus, so a
            // WebView nobody has touched yet reads as "nobody is looking".
            // Opening a session from a notification is exactly the case where
            // somebody IS -- and without this the row stayed up on every other
            // device after you had already dealt with it on the phone.
            view.requestFocus()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val home = homes[id] ?: return false
            val target = request.url
            if (target.host == home.host && target.port == home.port) return false
            // Anything that is not this server is the wider web, and belongs in
            // the phone's browser rather than inside a session viewer.
            return try {
                val intent = Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                view.context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
