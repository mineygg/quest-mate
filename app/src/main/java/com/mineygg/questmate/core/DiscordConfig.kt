package com.mineygg.questmate.core

import android.util.Base64
import java.util.TimeZone
import java.util.UUID
import org.json.JSONObject

/**
 * Discord client values and request headers.
 * Device values (OS, build, architecture) come from [DeviceProfile], the locale from [DiscordLocale].
 */
object DiscordConfig {
    const val API_BASE = "https://discord.com/api/v9"
    const val GATEWAY = "wss://gateway.discord.gg"

    /** Gateway transport: ETF + zstd-stream like the desktop client. false = plain JSON text. */
    const val GATEWAY_ETF = true

    // Client values come from ClientConfig (bundled file, or a newer downloaded one).
    private val client: ClientConfig.DiscordClient get() = ClientConfig.current.discord
    private val clientVersion: String get() = client.clientVersion
    private val buildNumber: Int get() = client.buildNumber
    private val nativeBuild: Int get() = client.nativeBuild
    private val electronVersion: String get() = client.electronVersion
    val chromeVersion: String get() = client.chromeVersion

    /** Gateway identify capabilities bitfield. */
    val capabilities: Int get() = client.capabilities

    /** Client heartbeat version sent in the op 40 QoS heartbeat. */
    val qosVersion: Int get() = client.qosVersion

    val agent: String
        get() = "Mozilla/5.0 (${DeviceProfile.UA_PLATFORM}) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "discord/$clientVersion Chrome/$chromeVersion Electron/$electronVersion Safari/537.36"

    // One launch per app process, like one Discord window.
    private val launchId = UUID.randomUUID().toString()
    private val launchSignature = UUID.randomUUID().toString()
    private val heartbeatSessionId = UUID.randomUUID().toString()

    /** Adds the device-only fields of the picked [DeviceProfile] (OS, build, architecture). */
    private fun JSONObject.device(p: DeviceProfile): JSONObject = this
        .put("os", DeviceProfile.OS_NAME)
        .put("os_version", p.osVersion)
        .put("os_sdk_version", p.osSdkVersion)
        .put("os_arch", DeviceProfile.ARCH)
        .put("app_arch", DeviceProfile.ARCH)

    fun props(): JSONObject = JSONObject()
        .device(DeviceProfile.current)
        .put("browser", "Discord Client")
        .put("release_channel", "stable")
        .put("client_version", clientVersion)
        .put("system_locale", DiscordLocale.current)
        .put("has_client_mods", false)
        .put("client_launch_id", launchId)
        .put("browser_user_agent", agent)
        .put("browser_version", chromeVersion)
        .put("client_build_number", buildNumber)
        .put("native_build_number", nativeBuild)
        .put("client_event_source", JSONObject.NULL)
        .put("launch_signature", launchSignature)
        .put("client_heartbeat_session_id", heartbeatSessionId)
        .put("client_app_state", "focused")

    /** Properties for the gateway identify only (the REST headers keep using [props]). */
    fun gatewayProps(installationId: String): JSONObject = JSONObject()
        .device(DeviceProfile.current)
        .put("browser", "Discord Client")
        .put("release_channel", "stable")
        .put("client_version", clientVersion)
        .put("os_version_is_release", true)
        .put("os_build_revision", DeviceProfile.current.osBuildRevision)
        .put("system_locale", DiscordLocale.current)
        .put("has_client_mods", false)
        .put("client_launch_id", launchId)
        .put("browser_user_agent", agent)
        .put("browser_version", electronVersion)
        .put("client_build_number", buildNumber)
        .put("native_build_number", nativeBuild)
        .put("client_event_source", JSONObject.NULL)
        .put("launch_signature", launchSignature)
        .put("client_app_state", "focused")
        .put("is_fast_connect", false)
        .put("gateway_connect_reasons", "AppSkeleton")
        .put("installation_id", installationId)

    const val ORIGIN = "https://discord.com"
    const val REFERER_HOME = "$ORIGIN/channels/@me"
    const val REFERER_QUESTS = "$ORIGIN/quest-home"

    /**
     * Chromium's GREASE brand for a major version (152 -> "Not?A_Brand" / "24"). Same formula Chromium uses:
     * the filler characters and the version are picked from fixed lists by the major version number.
     */
    fun greaseBrand(major: Int): Pair<String, String> {
        val chars = listOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
        val versions = listOf("8", "99", "24")
        val name = "Not${chars[major % chars.size]}A${chars[(major + 1) % chars.size]}Brand"
        return name to versions[major % versions.size]
    }

    /** The Sec-CH-UA value for the configured Chrome version. */
    val secChUa: String
        get() {
            val major = chromeVersion.substringBefore('.').toIntOrNull() ?: 0
            val (brand, ver) = greaseBrand(major)
            return "\"$brand\";v=\"$ver\", \"Chromium\";v=\"$major\""
        }

    /** Chromium reports the older IANA names for a few zones (Asia/Calcutta, not Asia/Kolkata); Android uses the new ones. */
    private val TZ_ALIASES = mapOf(
        "Asia/Kolkata" to "Asia/Calcutta",
        "Asia/Kathmandu" to "Asia/Katmandu",
        "Asia/Ho_Chi_Minh" to "Asia/Saigon",
        "Asia/Yangon" to "Asia/Rangoon",
        "Europe/Kyiv" to "Europe/Kiev"
    )

    val timezone: String
        get() = TimeZone.getDefault().id.let { TZ_ALIASES[it] ?: it }

    /**
     * Headers of a same-origin fetch() from the desktop client. Accept-Encoding is added in [Http.api].
     * Origin is only sent on non-GET requests and Content-Type only with a body, like Chromium does.
     */
    fun publicHeaders(method: String = "GET", referer: String = REFERER_HOME): Map<String, String> {
        val m = linkedMapOf(
            "Accept" to "*/*",
            "Accept-Language" to DiscordLocale.acceptLanguage,
            "Priority" to "u=1, i",
            "Referer" to referer,
            "sec-ch-ua" to secChUa,
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"${DeviceProfile.CH_PLATFORM}\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "User-Agent" to agent,
            "X-Debug-Options" to "bugReporterEnabled",
            "X-Discord-Locale" to DiscordLocale.current,
            "X-Discord-Timezone" to timezone,
            "X-Super-Properties" to Base64.encodeToString(props().toString().toByteArray(), Base64.NO_WRAP)
        )
        if (!method.equals("GET", ignoreCase = true)) m["Origin"] = ORIGIN
        return m
    }

    fun headers(token: String, method: String, referer: String, installationId: String): Map<String, String> =
        linkedMapOf("Authorization" to token) + publicHeaders(method, referer) + ("X-Installation-Id" to installationId)
}
