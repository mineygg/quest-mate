package com.mineygg.questmate.core

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * The sign-in WebView keeps its own browser data (cookies, local storage, cache) even after the app forgets the
 * token. Clearing it means the login page really asks for a login again, and no Discord session stays behind.
 */
object WebSession {
    /** Call on the main thread. [done] runs when everything is cleared. */
    fun clear(wv: WebView? = null, done: () -> Unit = {}) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies {
            cm.flush()
            WebStorage.getInstance().deleteAllData()
            wv?.clearCache(true)
            wv?.clearHistory()
            wv?.clearFormData()
            done()
        }
    }
}
