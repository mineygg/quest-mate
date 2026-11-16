package com.mineygg.questmate.core

import com.mineygg.questmate.data.Store
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * The `system_locale` / `X-Discord-Locale` value: the phone's own language and region when Discord supports it,
 * otherwise en-US. Read live, so it follows the phone's language setting.
 *
 * The phone's region wins. Only when the phone's locale has no region (plain "en", "es", "zh"...) is the country
 * Discord reports for this connection ([refreshCountry], /auth/location-metadata) used instead. It is the country
 * of the IP address, so on a VPN it is the VPN's country; that is why it is only a fallback.
 *
 * Supported list: https://discord.com/developers/docs/reference#locales
 */
object DiscordLocale {
    const val FALLBACK = "en-US"

    /** Locales Discord lists that carry a region (matched on language + region first). */
    private val EXACT = setOf("en-GB", "en-US", "es-ES", "es-419", "pt-BR", "sv-SE", "zh-CN", "zh-TW")

    /** Locales Discord lists by language only. */
    private val LANGUAGE_ONLY = setOf(
        "bg", "cs", "da", "de", "el", "fi", "fr", "hi", "hr", "hu", "id", "it", "ja", "ko", "lt",
        "nl", "no", "pl", "ro", "ru", "th", "tr", "uk", "vi"
    )

    /** Two-letter country Discord reported for this connection; null until the first lookup succeeded. */
    @Volatile
    var ipCountry: String? = null

    private const val COUNTRY_TTL_MS = 7L * 24 * 3600 * 1000

    val current: String get() = resolve(Locale.getDefault(), ipCountry)

    /** The phone's language list in order, e.g. ["en-GB", "en-IN"]. */
    private val languageTags: List<String>
        get() {
            val list = android.os.LocaleList.getDefault()
            return (0 until list.size()).map { list.get(it).toLanguageTag() }
                .filter { it.isNotBlank() && it != "und" }.distinct().ifEmpty { listOf(FALLBACK) }
        }

    /** Accept-Language the way Chromium writes it from the OS language list: "en-GB,en-IN;q=0.9,hi;q=0.8". */
    val acceptLanguage: String
        get() = languageTags.take(6).mapIndexed { i, tag -> if (i == 0) tag else "$tag;q=${(10 - i) / 10.0}" }.joinToString(",")

    /** Only the first language; this is what the gateway handshake carries. */
    val acceptLanguagePrimary: String get() = languageTags.first()

    /** Looks the country up (at most once a week, result kept in [Store]). Never throws; a failure keeps the old value. */
    suspend fun refreshCountry(store: Store) {
        if (ipCountry == null) ipCountry = store.ipCountry
        if (ipCountry != null && System.currentTimeMillis() - store.ipCountryAt < COUNTRY_TTL_MS) return
        val code = try {
            fetchCountry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        ipCountry = code
        store.setIpCountry(code, System.currentTimeMillis())
    }

    /** GET /auth/location-metadata: works without a token, like on Discord's login page. */
    private suspend fun fetchCountry(): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(DiscordConfig.API_BASE + "/auth/location-metadata").get().apply {
            DiscordConfig.publicHeaders().forEach { (k, v) -> header(k, v) }
        }.build()
        Http.api.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@use null
            JSONObject(res.body?.string().orEmpty()).optString("country_code").uppercase().takeIf { it.length == 2 }
        }
    }

    fun resolve(l: Locale, fallbackRegion: String? = null): String {
        // Android still reports a few legacy language codes.
        val lang = when (val raw = l.language.lowercase()) {
            "in" -> "id"
            "nb", "nn" -> "no"
            else -> raw
        }
        val region = l.country.uppercase().ifEmpty { fallbackRegion.orEmpty().uppercase() }

        // 1) Exact language + region Discord lists (en-GB, en-US, es-ES, pt-BR, sv-SE, zh-CN, zh-TW).
        "$lang-$region".takeIf { it in EXACT }?.let { return it }

        // 2) Language-level matches, where Discord has one entry for the language.
        return when {
            lang in LANGUAGE_ONLY -> lang
            lang == "es" -> if (region == "ES") "es-ES" else "es-419" // Latin America and the US
            lang == "pt" -> "pt-BR" // Discord has no pt-PT
            lang == "sv" -> "sv-SE"
            lang == "zh" ->
                if (l.script.equals("Hant", true) || region in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            else -> FALLBACK // includes every other English (en-IN, en-AU, ...) and unsupported languages
        }
    }
}
