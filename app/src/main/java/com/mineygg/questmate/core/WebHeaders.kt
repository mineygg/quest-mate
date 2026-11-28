package com.mineygg.questmate.core

import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.WeakHashMap
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Makes the sign-in WebView's API traffic look like the desktop client's REST traffic, and drops telemetry.
 *
 * 1. Every discord.com/api request that carries X-Super-Properties is re-issued through [Http.api] with the same
 *    header set (and order) as the app's own REST calls: User-Agent, Origin, Referer, Priority, sec-ch-ua*,
 *    Sec-Fetch-*, Accept-Encoding, X-Installation-Id, and a matching X-Super-Properties. Chrome's WebView can't
 *    set those from JS, and shouldInterceptRequest can't see request bodies, so [script] tunnels a string body
 *    to us in a header (X-QM-Body) that is removed again before the request leaves the device.
 * 2. Telemetry (see [isTelemetry]) is answered locally with an empty 204 and never leaves the device.
 * 3. The page's connection to the main gateway (gateway.discord.gg and its regional gateway-*.discord.gg names)
 *    is never opened, in both modes ([gatewayScript]). shouldInterceptRequest doesn't see WebSockets, so this is
 *    done in JS. Other sockets (remote-auth-gateway for QR login, voice) are untouched. The app's own
 *    Gateway.kt connection is not a WebView and is not affected.
 *
 * Only in desktop mode; mobile mode keeps the phone's own identity. A request that can't be re-issued cleanly
 * (e.g. a FormData body) is passed through untouched.
 */
object WebHeaders {
    private const val BODY_HEADER = "X-QM-Body"

    /** The installation_id sent as X-Installation-Id; set from the store at login time. */
    @Volatile
    var installationId: String? = null

    /** Called when an intercepted answer carries an installation_id and none is stored yet. */
    @Volatile
    var onInstallation: ((String) -> Unit)? = null

    private val handles = Collections.synchronizedMap(WeakHashMap<WebView, List<ScriptHandler>>())
    private val desktopMode = Collections.synchronizedMap(WeakHashMap<WebView, Boolean>())

    // --- telemetry ---------------------------------------------------------------------------------------------

    private val TELEMETRY_PATH = Regex("^/api/v\\d+/(science|metrics)(/.*)?$")
    private val TELEMETRY_HOST_PARTS = listOf("sentry", "adjust.", "crashlytics", "app-measurement", "firebaselogging")

    fun isTelemetry(host: String, path: String): Boolean =
        (host == "discord.com" && TELEMETRY_PATH.matches(path)) || TELEMETRY_HOST_PARTS.any { host.contains(it) }

    private fun empty204() = WebResourceResponse(
        "application/json",
        "utf-8",
        204,
        "No Content",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(ByteArray(0))
    )

    // --- install / intercept -----------------------------------------------------------------------------------

    /** Call from configure() each time the mode changes. */
    fun install(wv: WebView, desktop: Boolean) {
        desktopMode[wv] = desktop
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        handles.remove(wv)?.forEach { runCatching { it.remove() } }
        val origins = setOf("https://discord.com", "https://*.discord.com")
        val added = mutableListOf<ScriptHandler>()
        runCatching { added += WebViewCompat.addDocumentStartJavaScript(wv, gatewayScript(), origins) }
        if (desktop) runCatching { added += WebViewCompat.addDocumentStartJavaScript(wv, script(), origins) }
        handles[wv] = added
    }

    /** Use as the first line of shouldInterceptRequest. null = let the WebView load it normally. */
    fun intercept(wv: WebView?, req: WebResourceRequest): WebResourceResponse? {
        val host = req.url.host?.lowercase() ?: return null
        val path = req.url.path.orEmpty()
        if (isTelemetry(host, path)) return empty204()
        if (wv == null || desktopMode[wv] != true) return null
        if (host != "discord.com" || !path.startsWith("/api/")) return null
        val page = req.requestHeaders
        fun get(name: String) = page.entries.firstOrNull { it.key.equals(name, true) }?.value
        if (get("X-Super-Properties") == null) return null

        val method = req.method.uppercase()
        val hasBody = method != "GET" && method != "HEAD"
        val body = get(BODY_HEADER)
        if (hasBody && body == null) return null // body not tunnelled (FormData etc.): leave the request alone

        return try {
            val referer = get("Referer")?.takeIf { it.startsWith(DiscordConfig.ORIGIN) } ?: DiscordConfig.REFERER_HOME
            val out = linkedMapOf<String, String>()
            out.putAll(DiscordConfig.publicHeaders(method, referer)) // desktop header set, in desktop order
            // Everything else the page added itself (Authorization, Content-Type, X-Fingerprint, captcha, ...).
            page.forEach { (k, v) ->
                if (k.lowercase() !in OWNED) out[k] = v
            }
            get("Accept")?.let { out["Accept"] = it }
            installationId?.let { out["X-Installation-Id"] = it }

            val rb = if (hasBody) {
                val bytes = Base64.decode(body, Base64.DEFAULT)
                bytes.toRequestBody(get("Content-Type")?.toMediaTypeOrNull())
            } else {
                null
            }
            val request = Request.Builder().url(req.url.toString()).method(method, rb)
                .apply { out.forEach { (k, v) -> header(k, v) } }.build()

            val res = Http.api.newCall(request).execute()
            if (res.code in 300..399) {
                res.close()
                return null
            }
            val bytes = res.body?.bytes() ?: ByteArray(0)
            if (path.contains("/apex/experiments")) captureInstallation(bytes)
            val ct = res.header("Content-Type")
            val headers = HashMap<String, String>()
            res.headers.names().forEach { n ->
                if (n.lowercase() !in DROP_RESPONSE) headers[n] = res.headers.values(n).joinToString(", ")
            }
            WebResourceResponse(
                ct?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "application/json",
                ct?.substringAfter("charset=", "")?.trim()?.ifEmpty { null } ?: "utf-8",
                res.code,
                res.message.ifBlank { "OK" },
                headers,
                ByteArrayInputStream(bytes)
            )
        } catch (e: Exception) {
            // Failing closed: letting a tunnelled request through would leak X-QM-Body.
            RunBus.debug("WebView request $method $path failed: ${e.javaClass.simpleName}")
            WebResourceResponse(
                "application/json",
                "utf-8",
                502,
                "Bad Gateway",
                emptyMap(),
                ByteArrayInputStream("""{"message":"network error"}""".toByteArray())
            )
        }
    }

    private fun captureInstallation(bytes: ByteArray) {
        if (installationId != null) return
        val id = runCatching { JSONObject(String(bytes)).optString("installation") }.getOrNull()
        if (!id.isNullOrBlank()) {
            installationId = id
            onInstallation?.invoke(id)
        }
    }

    /** Headers we set ourselves (or that the browser/OkHttp own); never copied from the page. */
    private val OWNED = setOf(
        "user-agent", "accept", "accept-encoding", "accept-language", "origin", "referer", "priority", "host",
        "connection", "content-length", "cookie", "x-super-properties", "x-discord-locale", "x-discord-timezone",
        "x-debug-options", "x-installation-id", "x-qm-body", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
        "sec-ch-ua-full-version-list", "sec-ch-ua-platform-version", "sec-ch-ua-arch", "sec-ch-ua-bitness",
        "sec-ch-ua-model", "sec-ch-ua-wow64", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site", "sec-fetch-user",
        "x-requested-with"
    )
    private val DROP_RESPONSE = setOf("content-encoding", "content-length", "transfer-encoding", "set-cookie", "connection")

    // --- page script: no main-gateway WebSocket ------------------------------------------------------------------

    /**
     * Replaces window.WebSocket with a Proxy: sockets to the main gateway get a stub that stays in CONNECTING and
     * never touches the network (close() on it works, so the client's own timeout/retry logic stays quiet);
     * every other URL is constructed normally, so instanceof, WebSocket.OPEN and friends keep working.
     */
    private fun gatewayScript(): String = """
(function () {
  if (window.__qmWs) return; window.__qmWs = 1;
  var W = window.WebSocket;
  var RE = /^gateway(-[a-z0-9-]+)?\.discord\.gg${'$'}/i;
  function blocked(u) { try { return RE.test(new URL(String(u), location.href).hostname); } catch (e) { return false; } }
  function stub(url) {
    var t = new EventTarget();
    t.url = String(url); t.readyState = 0; t.bufferedAmount = 0; t.extensions = ''; t.protocol = ''; t.binaryType = 'blob';
    t.CONNECTING = 0; t.OPEN = 1; t.CLOSING = 2; t.CLOSED = 3;
    ['open', 'message', 'error', 'close'].forEach(function (n) {
      t['on' + n] = null;
      t.addEventListener(n, function (e) { var h = t['on' + n]; if (typeof h === 'function') h.call(t, e); });
    });
    t.send = function () { throw new DOMException('Still in CONNECTING state', 'InvalidStateError'); };
    t.close = function (code, reason) {
      if (t.readyState >= 2) return;
      t.readyState = 3;
      setTimeout(function () {
        var e = new Event('close'); e.code = code || 1000; e.reason = reason || ''; e.wasClean = true; t.dispatchEvent(e);
      }, 0);
    };
    return t;
  }
  try {
    window.WebSocket = new Proxy(W, {
      construct: function (target, args, newTarget) {
        if (blocked(args[0])) return stub(args[0]);
        return Reflect.construct(target, args, newTarget);
      }
    });
  } catch (e) {}
})();
"""

    // --- page script: body tunnel -------------------------------------------------------------------------------

    /** Hands string bodies of X-Super-Properties requests to [intercept]; touches nothing else. */
    private fun script(): String = """
(function () {
  if (window.__qmHdr) return; window.__qmHdr = 1;
  var T = '$BODY_HEADER';
  function ours(u) {
    try { var x = new URL(u, location.href); return x.origin === location.origin && x.pathname.indexOf('/api/') === 0; }
    catch (e) { return false; }
  }
  function enc(s) {
    var b = new TextEncoder().encode(s), r = '';
    for (var i = 0; i < b.length; i += 8192) r += String.fromCharCode.apply(null, b.subarray(i, i + 8192));
    return btoa(r);
  }
  var of = window.fetch;
  window.fetch = function (input, init) {
    try {
      var url = typeof input === 'string' ? input : (input && input.url) || String(input);
      if (ours(url)) {
        var m = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
        var h = new Headers((init && init.headers) || (input && input.headers) || undefined);
        if (m !== 'GET' && m !== 'HEAD' && h.has('x-super-properties')) {
          var b = init && init.body;
          if (b === undefined || b === null || typeof b === 'string') {
            h.set(T, b ? enc(b) : '');
            init = Object.assign({}, init || {}, { headers: h });
          }
        }
      }
    } catch (e) {}
    return of.call(this, input, init);
  };
  var X = XMLHttpRequest.prototype, oo = X.open, os = X.send, oh = X.setRequestHeader;
  X.open = function (m, u) { this.__qm = ours(u) && !/^(GET|HEAD)${'$'}/i.test(m); this.__qs = 0; return oo.apply(this, arguments); };
  X.setRequestHeader = function (k, v) {
    if (this.__qm && String(k).toLowerCase() === 'x-super-properties') this.__qs = 1;
    return oh.call(this, k, v);
  };
  X.send = function (b) {
    try { if (this.__qm && this.__qs && (b === undefined || b === null || typeof b === 'string')) oh.call(this, T, b ? enc(b) : ''); } catch (e) {}
    return os.apply(this, arguments);
  };
})();
"""
}
