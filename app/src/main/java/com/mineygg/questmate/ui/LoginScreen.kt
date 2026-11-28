package com.mineygg.questmate.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mineygg.questmate.MainViewModel
import com.mineygg.questmate.core.DeviceHeaders
import com.mineygg.questmate.core.WebHeaders
import kotlinx.coroutines.delay

private const val LOGIN_URL = "https://discord.com/login"

/**
 * Reads the signed-in session's token from Discord's own web app, inside the WebView. The page is only asked once
 * the user is past the login screen. The token goes straight to the encrypted store and nowhere else.
 */
private const val TOKEN_JS = """
(function () {
  try {
    var found = '';
    var chunk = window.webpackChunkdiscord_app;
    if (!chunk) return '';
    chunk.push([[Symbol()], {}, function (req) {
      for (var id in req.c) {
        var e = req.c[id] && req.c[id].exports;
        if (!e) continue;
        var cands = [e, e.default, e.Z, e.ZP];
        for (var i = 0; i < cands.length; i++) {
          var c = cands[i];
          if (c && typeof c.getToken === 'function') {
            try { var t = c.getToken(); if (typeof t === 'string' && t.length > 20) { found = t; return; } } catch (x) {}
          }
        }
      }
    }]);
    return found;
  } catch (e) { return ''; }
})()
"""

private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")

/**
 * One configuration for the main WebView AND the popup window it opens, so both present the same browser identity
 * (same as Ergon's sign-in): its own headers, and no "X-Requested-With: <package>" header.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun configure(wv: WebView, cm: CookieManager, desktop: Boolean) {
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        userAgentString = DeviceHeaders.userAgent(desktop)
    }
    DeviceHeaders.applyClientHints(wv, desktop)
    // Discord's page lays out inside #app-mount, which Android WebView leaves 0px tall (a known WebView quirk with
    // absolutely positioned, 100%-high elements), so the whole form is clipped. FIX_JS gives it the real height.
    if (wv.tag != "ds" && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(wv, FIX_JS, setOf("https://discord.com", "https://*.discord.com"))
            wv.tag = "ds"
        }
    }
    WebHeaders.install(wv, desktop)
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(wv.settings, emptySet())
    }
    wv.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
    cm.setAcceptCookie(true)
    cm.setAcceptThirdPartyCookies(wv, true)
    if ((wv.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
}

/** Links that try to open another app (discord://, intent://) are blocked; the page carries on on the web. */
private fun blockAppLink(view: WebView?, request: WebResourceRequest?): Boolean {
    val uri = request?.url ?: return false
    val scheme = uri.scheme?.lowercase().orEmpty()
    if (scheme in WEB_SCHEMES) return false
    if (scheme == "intent") {
        val fallback = runCatching {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
        }.getOrNull()
        if (fallback != null && fallback.startsWith("https://")) view?.loadUrl(fallback)
    }
    return true
}

/**
 * Gives html, body and Discord's #app-mount root the real window height in pixels (window.innerHeight), and keeps it
 * right on resize. Percent heights collapse to 0 here, pixel heights don't.
 */
private const val FIX_JS =
    "(function(){if(window.__qmFix)return;window.__qmFix=1;var s=document.createElement('style');" +
        "(document.head||document.documentElement).appendChild(s);" +
        "function f(){var h=window.innerHeight+'px';" +
        "s.textContent='html,body{height:'+h+'!important;min-height:'+h+'!important}" +
        "#app-mount,[class*=appMount]{position:absolute!important;top:0!important;left:0!important;" +
        "width:100%!important;height:'+h+'!important}'}" +
        "f();addEventListener('resize',f);document.addEventListener('DOMContentLoaded',f);addEventListener('load',f)})()"

@Composable
fun LoginScreen(vm: MainViewModel, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cm = remember { CookieManager.getInstance() }
    val accepted by vm.disclaimerAccepted.collectAsStateWithLifecycle()
    var paste by rememberSaveable { mutableStateOf(false) }
    var pageUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var checking by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var popup by remember { mutableStateOf<WebView?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var desktop by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) { vm.prepareLoginInstallation() }

    /** True from the moment the page is past the login screen until the token has been read. */
    var signedIn by remember { mutableStateOf(false) }

    // Once the user is past the login page and the page has loaded, ask it for the token. No cooldown: the web
    // client's gateway connection is blocked (WebHeaders), so there is no start-up to wait for.
    LaunchedEffect(accepted, paste) {
        if (!accepted || paste) return@LaunchedEffect
        var lastTried = ""
        while (true) {
            delay(1000)
            val wv = webView ?: continue
            val uri = runCatching { Uri.parse(pageUrl) }.getOrNull() ?: continue
            val loggedIn = uri.host?.endsWith("discord.com") == true &&
                uri.path.orEmpty().let { it.startsWith("/channels") || it.startsWith("/app") || it.startsWith("/library") }
            signedIn = loggedIn
            // Only once the page has finished loading.
            if (!loggedIn || progress < 100) continue
            if (checking) continue
            wv.evaluateJavascript(TOKEN_JS) { raw ->
                val token = raw?.trim()?.trim('"').orEmpty()
                if (token.length > 20 && token != lastTried) {
                    lastTried = token
                    checking = true
                    vm.signIn(token) { problem ->
                        checking = false
                        if (problem == null) {
                            onClose()
                        } else {
                            error = problem
                        }
                    }
                }
            }
        }
    }

    BackHandler(enabled = popup != null) {
        popup?.let { runCatching { it.destroy() } }
        popup = null
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { webView?.destroy() } }
    }

    Surface(Modifier.fillMaxSize(), color = cs.background, contentColor = cs.onBackground) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                Column(Modifier.weight(1f)) {
                    Text("Connect Discord", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (paste) "Paste a token" else "Sign in on Discord's own page",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
                if (!paste) {
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = accepted) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        val target = popup ?: webView
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Back") },
                                enabled = menuOpen && target?.canGoBack() == true,
                                onClick = {
                                    menuOpen = false
                                    target?.goBack()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Forward") },
                                enabled = menuOpen && target?.canGoForward() == true,
                                onClick = {
                                    menuOpen = false
                                    target?.goForward()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reload") },
                                enabled = target != null,
                                onClick = {
                                    menuOpen = false
                                    loadError = null
                                    target?.reload()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (desktop) "Switch to mobile mode" else "Switch to desktop mode") },
                                onClick = {
                                    menuOpen = false
                                    desktop = !desktop
                                    loadError = null
                                    webView?.let {
                                        configure(it, cm, desktop)
                                        it.reload()
                                    }
                                }
                            )
                        }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !paste, onClick = { paste = false }, label = { Text("Sign in") })
                FilterChip(selected = paste, onClick = { paste = true }, label = { Text("Paste token") })
            }

            if (paste) {
                PasteToken(vm, enabled = accepted, onDone = onClose)
            } else {
                UrlBar(if (popup != null) "" else pageUrl)
                Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 16.dp)) {
                    if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (accepted) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    configure(this, cm, desktop)
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            progress = newProgress
                                        }

                                        // Sign-in pages may open a popup window that reports back to this page.
                                        override fun onCreateWindow(
                                            view: WebView?,
                                            isDialog: Boolean,
                                            isUserGesture: Boolean,
                                            resultMsg: Message?
                                        ): Boolean {
                                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                            val parent = view ?: return false
                                            val child = WebView(parent.context)
                                            configure(child, cm, desktop)
                                            child.webViewClient = object : WebViewClient() {
                                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                                                    request?.let { WebHeaders.intercept(view, it) }

                                                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) =
                                                    blockAppLink(v, r)
                                            }
                                            child.webChromeClient = object : WebChromeClient() {
                                                override fun onCloseWindow(window: WebView?) {
                                                    popup = null
                                                    runCatching { window?.destroy() }
                                                }
                                            }
                                            popup = child
                                            transport.webView = child
                                            resultMsg.sendToTarget()
                                            return true
                                        }
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                                            request?.let { WebHeaders.intercept(view, it) }

                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) =
                                            blockAppLink(view, request)

                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            loadError = null
                                            pageUrl = url.orEmpty()
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            cm.flush()
                                            pageUrl = url.orEmpty()
                                            view?.evaluateJavascript(FIX_JS, null)
                                        }

                                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, err: SslError?) {
                                            handler?.cancel()
                                            loadError = "A secure connection couldn't be made"
                                        }

                                        override fun onReceivedHttpError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            resp: WebResourceResponse?
                                        ) {
                                            if (request?.isForMainFrame == true && (resp?.statusCode ?: 0) >= 400) {
                                                loadError = "The site answered ${resp?.statusCode}"
                                            }
                                        }

                                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                            loadError = "The browser engine stopped. Tap Retry."
                                            return true
                                        }

                                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, err: WebResourceError?) {
                                            if (request?.isForMainFrame == true) {
                                                loadError = err?.description?.toString() ?: "Couldn't load the page"
                                            }
                                        }
                                    }
                                    webView = this
                                    loadUrl(LOGIN_URL)
                                }
                            }
                        )
                    }

                    popup?.let { w ->
                        Box(Modifier.fillMaxSize().background(cs.background)) {
                            AndroidView(modifier = Modifier.fillMaxSize(), factory = { w })
                            IconButton(
                                onClick = {
                                    popup = null
                                    runCatching { w.destroy() }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .background(cs.surface.copy(alpha = 0.85f), CircleShape)
                            ) { Icon(Icons.Default.Close, contentDescription = "Close popup") }
                        }
                    }

                    loadError?.let { msg ->
                        Column(
                            Modifier.fillMaxSize().background(cs.background).padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Page didn't load", style = MaterialTheme.typography.titleMedium)
                            Text(msg, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            Button(
                                onClick = {
                                    loadError = null
                                    webView?.reload()
                                },
                                modifier = Modifier.padding(top = 16.dp)
                            ) { Text("Retry") }
                        }
                    }
                }
                Text(
                    error ?: when {
                        checking -> "Checking your sign-in…"
                        signedIn -> "Signed in. Finishing up, this closes by itself. Please wait."
                        else -> "Log in as usual. This closes by itself when it's done."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) cs.error else cs.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }

    if (!accepted) {
        DisclaimerDialog(onProceed = { vm.acceptDisclaimer() }, onExit = onClose)
    }
}

/** Read-only address bar: lock + host (bold) + path. Query strings are hidden. */
@Composable
private fun UrlBar(url: String) {
    val cs = MaterialTheme.colorScheme
    val extra = LocalExtra.current
    val uri = remember(url) { runCatching { Uri.parse(url) }.getOrNull() }
    val host = uri?.host.orEmpty()
    val secure = uri?.scheme == "https"
    val path = uri?.path.orEmpty().let { if (it == "/") "" else it }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (secure || url.isEmpty()) Icons.Default.Lock else Icons.Default.Warning,
            contentDescription = if (secure) "Secure connection" else "Not secure",
            tint = if (secure || url.isEmpty()) extra.success else extra.warning,
            modifier = Modifier.size(16.dp)
        )
        HSpace(8)
        Text(
            if (host.isEmpty()) {
                buildAnnotatedString { withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append("Loading…") } }
            } else {
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = cs.onSurface)) { append(host) }
                    withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append(path) }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PasteToken(vm: MainViewModel, enabled: Boolean, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Paste the token of the account you want to use. It is checked with Discord, then stored encrypted on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant
        )
        VSpace(12)
        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                error = null
            },
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )
        VSpace(12)
        Button(
            onClick = {
                busy = true
                vm.signIn(token) { problem ->
                    busy = false
                    if (problem == null) onDone() else error = problem
                }
            },
            enabled = enabled && token.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text(if (busy) "Checking…" else "Connect") }
    }
}

@Composable
private fun DisclaimerDialog(onProceed: () -> Unit, onExit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val points = listOf(
        "Unofficial app" to "Quest Mate is an independent, open-source project. It is not affiliated with, " +
            "endorsed by or sponsored by Discord Inc.",
        "Use at your own risk" to "Automating quests may go against Discord's Terms of Service. Discord can " +
            "restrict or ban accounts at its discretion, and you are solely responsible for what happens to yours.",
        "Your sign-in" to "You sign in on Discord's own page. The session token is kept encrypted on this " +
            "device and sent only to Discord's servers. Never share it with anyone.",
        "No warranty" to "Provided “as is” under the GNU GPL v3, without any warranty. It can stop working at " +
            "any time if Discord changes."
    )
    AlertDialog(
        onDismissRequest = onExit,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Before you continue") },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                points.forEach { (head, body) ->
                    Column {
                        Text(head, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onProceed) { Text("I understand, proceed") } },
        dismissButton = { TextButton(onClick = onExit) { Text("Exit") } }
    )
}
