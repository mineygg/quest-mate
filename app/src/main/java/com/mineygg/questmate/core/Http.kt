package com.mineygg.questmate.core

import android.webkit.CookieManager
import com.github.luben.zstd.ZstdInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
import org.brotli.dec.BrotliInputStream

object Http {
    /** Only used by the login screen's optional "Desktop" browser mode. */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36"

    /** API calls: no cookie jar, headers are set by hand per request. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Shares cookies with the WebView. */
    val web: OkHttpClient by lazy { client.newBuilder().cookieJar(WebCookieJar).build() }

    /** Send the REST calls with the WebView's cookies (__dcfduid, cf_clearance, ...) like the desktop client. false = no cookies. */
    private const val API_COOKIES = true

    /** Discord REST calls: advertises gzip/deflate/br/zstd like Chromium and decodes the answer. */
    val api: OkHttpClient by lazy {
        (if (API_COOKIES) web else client).newBuilder().addInterceptor(ContentDecoder).build()
    }
}

/**
 * OkHttp only decodes gzip, and only when it set Accept-Encoding itself. Since we send the browser's value
 * (gzip, deflate, br, zstd) the server may answer in any of the four, so they are all decoded here.
 */
object ContentDecoder : Interceptor {
    private const val ACCEPT = "gzip, deflate, br, zstd"

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val res = chain.proceed(
            if (req.header("Accept-Encoding") == null) req.newBuilder().header("Accept-Encoding", ACCEPT).build() else req
        )
        val body = res.body ?: return res
        if (res.request.method == "HEAD" || res.code == 204 || res.code == 304 || body.contentLength() == 0L) return res
        val raw = body.byteStream()
        val plain: InputStream = when (res.header("Content-Encoding")?.trim()?.lowercase()) {
            "gzip", "x-gzip" -> Deferred(raw) { GZIPInputStream(raw) }
            "deflate" -> Deferred(raw) { InflaterInputStream(raw) }
            "br" -> Deferred(raw) { BrotliInputStream(raw) }
            "zstd" -> Deferred(raw) { ZstdInputStream(raw) }
            else -> return res
        }
        return res.newBuilder()
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Length")
            .body(plain.source().buffer().asResponseBody(body.contentType(), -1L))
            .build()
    }

    /** Opens the decoder on the first read, so a decoder that reads a header on creation fails where the caller can handle it. */
    private class Deferred(private val raw: InputStream, private val open: () -> InputStream) : InputStream() {
        private var s: InputStream? = null
        private fun stream(): InputStream = s ?: open().also { s = it }
        override fun read(): Int = stream().read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = stream().read(b, off, len)
        override fun close() {
            runCatching { s?.close() }
            raw.close()
        }
    }
}

/**
 * Bridges OkHttp to the WebView's cookie store, so a Cloudflare clearance earned once by the hidden
 * browser is automatically reused by plain requests (and vice versa), like a real browser would.
 */
object WebCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = CookieManager.getInstance()
        cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
        cm.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
