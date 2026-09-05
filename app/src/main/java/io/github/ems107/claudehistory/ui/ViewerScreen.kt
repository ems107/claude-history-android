package io.github.ems107.claudehistory.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import io.github.ems107.claudehistory.BuildConfig
import io.github.ems107.claudehistory.net.Connection

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
    val server = remember(serverId) { viewModel.serverOf(serverId) }
    val states by viewModel.states.collectAsState()
    val connecting by viewModel.connecting.collectAsState()
    val state = states[serverId]

    LaunchedEffect(serverId) {
        if (state !is Connection.Ready) viewModel.refresh(serverId)
    }

    /** Back means back through the pages first, and out of the viewer last. */
    fun goBack() {
        val view = WebViewCache.get(serverId)
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    BackHandler { goBack() }

    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = server?.label() ?: "claude-history",
            onBack = { goBack() },
            onHome = onBack,
            onReload = { WebViewCache.get(serverId)?.reload() },
        )

        OldWebViewWarning(context)

        Box(Modifier.fillMaxSize()) {
            when (val current = state) {
                is Connection.Ready -> {
                    val cookie = viewModel.sessionCookie(current.baseUrl)
                    val target = current.baseUrl.trimEnd('/') + startPath
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> WebViewCache.obtain(ctx, serverId) },
                        update = { view ->
                            WebViewCache.load(view, serverId, current.baseUrl, target, cookie)
                        },
                    )
                }

                null -> CentredMessage("Connecting...", spinner = serverId in connecting)
                is Connection.Refused -> CentredMessage(current.detail)
                is Connection.Unreachable -> CentredMessage(current.detail)
            }
        }
    }
}

@Composable
private fun ViewerBar(
    title: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onReload: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("←") }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            TextButton(onClick = onReload) { Text("↻") }
            TextButton(onClick = onHome) { Text("Servers") }
        }
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
            modifier = Modifier.padding(10.dp),
        )
    }
}

private const val MIN_CHROME = 111

/** The layout width the pages are drawn at, whatever the phone's own width is. */
private const val DESKTOP_WIDTH = 1280

/**
 * Draw the page at desktop width and scale it down to fit, rather than let it
 * reflow into a 360 px column.
 *
 * claude-history's pages declare `width=device-width`, which is right for a site
 * built for phones and wrong for this one: at 360 px the header wraps, the two
 * columns beside a session pile up and the list falls off the side. This version
 * is deliberately not adapting that interface -- so the honest thing is to show
 * it as it was designed, small, and let the reader zoom. It replaces the meta tag
 * rather than fighting it, because `useWideViewPort` is only consulted when the
 * page does not declare one.
 */
private val WIDE_VIEWPORT = """
    (function () {
      var m = document.querySelector('meta[name="viewport"]');
      if (!m) { m = document.createElement('meta'); m.setAttribute('name', 'viewport'); document.head.appendChild(m); }
      var scale = window.screen.width / $DESKTOP_WIDTH;
      m.setAttribute('content', 'width=$DESKTOP_WIDTH, initial-scale=' + scale + ', minimum-scale=' + scale);
    })();
""".trimIndent()

/**
 * One WebView per server, kept for as long as the app is alive.
 *
 * Switching servers and coming back must not throw away the page you were
 * reading, and a conversation is expensive to render -- so the views outlive the
 * composables that show them, and only the Activity going away destroys them.
 */
object WebViewCache {
    private val views = mutableMapOf<String, WebView>()
    private val loaded = mutableMapOf<String, String>()
    private val homes = mutableMapOf<String, Uri>()

    fun get(id: String): WebView? = views[id]

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
            // The pages are a desktop layout and stay one in this version: show
            // them whole and let the reader zoom, rather than pretend otherwise.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = ServerWebViewClient(id)
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
    }

    fun enableDebugging() {
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }

    private class ServerWebViewClient(private val id: String) : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(WIDE_VIEWPORT, null)
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
