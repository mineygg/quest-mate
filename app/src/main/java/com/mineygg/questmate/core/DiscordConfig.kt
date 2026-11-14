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

    /** Request headers for calls that are made without being signed in. */
    fun publicHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "User-Agent" to agent,
        "Origin" to "https://discord.com",
        "Referer" to "https://discord.com/channels/@me",
        "X-Discord-Locale" to DiscordLocale.current,
        "X-Discord-Timezone" to TimeZone.getDefault().id,
        "X-Super-Properties" to Base64.encodeToString(props().toString().toByteArray(), Base64.NO_WRAP),
        "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"${chromeVersion.substringBefore('.')}\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"${DeviceProfile.CH_PLATFORM}\"",
        "X-Debug-Options" to "bugReporterEnabled"
    )

    fun headers(token: String): Map<String, String> = mapOf("Authorization" to token) + publicHeaders()
}
