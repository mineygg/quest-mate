package com.mineygg.questmate.core

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * The browser identity of the sign-in WebView, switchable in the login menu.
 *
 * Desktop: Windows Chrome headers like the quest requests use (see [DeviceProfile]), without the Discord-client parts
 * (no "discord/..." or "Electron/..."). Mobile: this phone's own browser identity, the way Ergon does it.
 * In both, the Chrome version is the device's real WebView engine, and the User-Agent string and the client hints
 * (Sec-CH-UA, navigator.userAgentData) say the same thing, with every field filled in.
 */
object DeviceHeaders {
    @Volatile
    private var realUa: String? = null

    @Volatile
    private var realChrome: String? = null

    private val fallbackUa: String
        get() = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/${DiscordConfig.chromeVersion} Mobile Safari/537.36"

    /** This phone's browser identity; before [init] (or if it can't be read) a typical Pixel on the config's Chrome. */
    private val phoneUa: String get() = realUa ?: fallbackUa

    private val chrome: String get() = realChrome ?: DiscordConfig.chromeVersion

    /** Call once from Application.onCreate (main thread). */
    fun init(ctx: Context) {
        val real = runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull() ?: return
        // A browser's UA has no "; wv" and no "Version/4.0" (those mark a bare WebView).
        realUa = real.replace("; wv", "").replace("Version/4.0 ", "")
        Regex("""Chrome/([\d.]+)""").find(real)?.groupValues?.get(1)?.let { realChrome = it }
    }

    fun userAgent(desktop: Boolean): String =
        if (desktop) {
            "Mozilla/5.0 (${DeviceProfile.UA_PLATFORM}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        } else {
            phoneUa
        }

    fun applyClientHints(wv: WebView, desktop: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        runCatching {
            fun brand(name: String, maj: String, ver: String) =
                UserAgentMetadata.BrandVersion.Builder().setBrand(name).setMajorVersion(maj).setFullVersion(ver).build()
            val b = UserAgentMetadata.Builder()
                .setBrandVersionList(
                    listOf(
                        DiscordConfig.greaseBrand(chrome.substringBefore('.').toIntOrNull() ?: 0).let { (n, v) -> brand(n, v, "$v.0.0.0") },
                        brand("Chromium", chrome.substringBefore('.'), chrome)
                    )
                )
                .setFullVersion(chrome)
            if (desktop) {
                b.setPlatform(DeviceProfile.CH_PLATFORM).setPlatformVersion(DeviceProfile.current.chPlatformVersion)
                    .setArchitecture(DeviceProfile.CH_ARCH).setBitness(DeviceProfile.CH_BITNESS)
                    .setModel("").setMobile(false).setWow64(false)
            } else {
                b.setPlatform("Android").setPlatformVersion(Build.VERSION.RELEASE).setModel(Build.MODEL)
                    .setArchitecture("").setBitness(0).setMobile(true).setWow64(false)
            }
            WebSettingsCompat.setUserAgentMetadata(wv.settings, b.build())
        }
    }
}
